package moe.caa.multilogin.velocity.injector;

import static com.google.common.collect.Iterables.getLast;
import static com.velocitypowered.api.network.ProtocolVersion.SUPPORTED_VERSIONS;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import io.netty.util.collection.IntObjectMap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import moe.caa.multilogin.api.internal.injector.Injector;
import moe.caa.multilogin.api.internal.logger.LoggerProvider;
import moe.caa.multilogin.api.internal.main.MultiCoreAPI;
import moe.caa.multilogin.api.internal.util.reflect.ReflectUtil;
import moe.caa.multilogin.velocity.injector.handler.MultiInitialLoginSessionHandler;
import moe.caa.multilogin.velocity.injector.redirect.auth.MultiEncryptionResponse;
import moe.caa.multilogin.velocity.injector.redirect.chat.PlayerSessionPacketBlocker;

/**
 * Velocity 注入程序
 */
public class VelocityInjector implements Injector {

    @Override
    public void inject(MultiCoreAPI multiCoreAPI) throws Throwable {
        validateCompatibility();

        StateRegistry.PacketRegistry serverbound = getServerboundPacketRegistry(StateRegistry.LOGIN);
        int redirected = redirectInput(
                serverbound,
                EncryptionResponsePacket.class,
                () -> new MultiEncryptionResponse(multiCoreAPI)
        );
        if (redirected == 0) {
            throw new IllegalStateException(
                    "Velocity 3.5.1 compatibility check failed: "
                            + "EncryptionResponsePacket was not registered"
            );
        }
    }

    void validateCompatibility() throws Throwable {
        MultiInitialLoginSessionHandler.init();
        requireField(StateRegistry.class, "serverbound", StateRegistry.PacketRegistry.class);
        requireField(StateRegistry.PacketRegistry.class, "versions", Map.class);
        requireField(
                StateRegistry.PacketRegistry.ProtocolRegistry.class,
                "packetIdToSupplier",
                IntObjectMap.class
        );
        requireField(
                StateRegistry.PacketRegistry.ProtocolRegistry.class,
                "packetClassToId",
                Map.class
        );
        requireField(StateRegistry.PacketMapping.class, "id", int.class);
        requireField(StateRegistry.PacketMapping.class, "protocolVersion", ProtocolVersion.class);
        requireField(
                StateRegistry.PacketMapping.class,
                "lastValidProtocolVersion",
                ProtocolVersion.class
        );
        requireField(StateRegistry.PacketMapping.class, "encodeOnly", boolean.class);
        StateRegistry.PacketMapping.class.getDeclaredConstructor(
                int.class,
                ProtocolVersion.class,
                ProtocolVersion.class,
                boolean.class
        );
    }

    @Override
    public void registerChatSession(Map<Integer, Integer> packetMapping) {
        try {
            StateRegistry.PacketRegistry serverbound =
                    getServerboundPacketRegistry(StateRegistry.PLAY);

            var entries = new LinkedList<>(new TreeMap<>(packetMapping).entrySet());
            for (int index = 0; index < entries.size(); index++) {
                Map.Entry<Integer, Integer> entry = entries.get(index);
                ProtocolVersion protocolVersion =
                        ProtocolVersion.getProtocolVersion(entry.getKey());
                if (protocolVersion.isUnknown() || !protocolVersion.isSupported()) {
                    LoggerProvider.getLogger().warn(
                            "Ignoring PlayerSessionPacketBlocker mapping for unsupported "
                            + "protocol version: " + entry.getKey()
                    );
                    continue;
                }
                ProtocolVersion nextProtocolVersion = index + 1 < entries.size()
                        ? ProtocolVersion.getProtocolVersion(entries.get(index + 1).getKey())
                        : null;
                ProtocolVersion lastCompatibleVersion = findLastCompatibleVersion(
                        serverbound,
                        protocolVersion,
                        nextProtocolVersion,
                        entry.getValue(),
                        PlayerSessionPacketBlocker.class
                );
                LoggerProvider.getLogger().debug(
                        "Register PlayerSessionPacketBlocker for protocol version: "
                                + entry.getKey() + " through "
                                + lastCompatibleVersion.getProtocol()
                );
                registerPacket(
                        serverbound,
                        PlayerSessionPacketBlocker.class,
                        PlayerSessionPacketBlocker::new,
                        new StateRegistry.PacketMapping[]{
                                createPacketMapping(
                                        entry.getValue(),
                                        protocolVersion,
                                        lastCompatibleVersion,
                                        false
                                )
                        }
                );
            }
        } catch (Throwable throwable) {
            LoggerProvider.getLogger().error(
                    "Unable to register PlayerSessionPacketBlocker, "
                            + "chat session blocker does not work as expected.",
                    throwable
            );
            throw new IllegalStateException(
                    "Velocity 3.5.1 compatibility check failed while registering "
                            + "chat session mappings",
                    throwable
            );
        }
    }

    private ProtocolVersion findLastCompatibleVersion(
            StateRegistry.PacketRegistry bound,
            ProtocolVersion from,
            ProtocolVersion next,
            int packetId,
            Class<? extends MinecraftPacket> packetClass
    ) throws NoSuchFieldException, IllegalAccessException {
        ProtocolVersion lastCompatible = from;
        boolean inRange = false;
        for (ProtocolVersion protocol : SUPPORTED_VERSIONS) {
            if (protocol == from) {
                inRange = true;
            }
            if (!inRange || protocol == next) {
                continue;
            }
            if (next != null && protocol.greaterThan(next)) {
                break;
            }

            StateRegistry.PacketRegistry.ProtocolRegistry registry =
                    (StateRegistry.PacketRegistry.ProtocolRegistry)
                            getProtocolRegistriesMap(bound).get(protocol);
            if (registry == null) {
                break;
            }
            if (!isPacketIdCompatible(registry, packetId, packetClass)) {
                if (protocol == from) {
                    return from;
                }
                LoggerProvider.getLogger().warn(
                        "Stopped inherited PlayerSessionPacketBlocker mapping before "
                                + protocol.getProtocol() + " because packet id "
                                + packetId + " is already occupied"
                );
                break;
            }
            lastCompatible = protocol;
            if (next == null) {
                break;
            }
        }
        return lastCompatible;
    }

    @SuppressWarnings("unchecked")
    private boolean isPacketIdCompatible(
            StateRegistry.PacketRegistry.ProtocolRegistry registry,
            int packetId,
            Class<? extends MinecraftPacket> packetClass
    ) throws NoSuchFieldException, IllegalAccessException {
        Field packetIdToSupplierField = requireField(
                StateRegistry.PacketRegistry.ProtocolRegistry.class,
                "packetIdToSupplier",
                IntObjectMap.class
        );
        IntObjectMap<Supplier<? extends MinecraftPacket>> packetIdToSupplier =
                (IntObjectMap<Supplier<? extends MinecraftPacket>>)
                        packetIdToSupplierField.get(registry);
        Supplier<? extends MinecraftPacket> registeredSupplier =
                packetIdToSupplier.get(packetId);
        return registeredSupplier == null
                || packetClass.isInstance(registeredSupplier.get());
    }

    StateRegistry.PacketRegistry getServerboundPacketRegistry(StateRegistry stateRegistry)
            throws NoSuchFieldException, IllegalAccessException {
        Field serverboundField = requireField(
                StateRegistry.class,
                "serverbound",
                StateRegistry.PacketRegistry.class
        );
        return (StateRegistry.PacketRegistry) serverboundField.get(stateRegistry);
    }

    @SuppressWarnings("unchecked")
    int redirectInput(
            StateRegistry.PacketRegistry bound,
            Class<? extends MinecraftPacket> originalClass,
            Supplier<? extends MinecraftPacket> supplierRedirect
    ) throws NoSuchFieldException, IllegalAccessException {
        Field packetIdToSupplierField = requireField(
                StateRegistry.PacketRegistry.ProtocolRegistry.class,
                "packetIdToSupplier",
                IntObjectMap.class
        );
        int redirected = 0;
        for (Object protocolRegistry : getProtocolRegistries(bound)) {
            IntObjectMap<Supplier<? extends MinecraftPacket>> packetIdToSupplier =
                    (IntObjectMap<Supplier<? extends MinecraftPacket>>)
                            packetIdToSupplierField.get(protocolRegistry);
            for (IntObjectMap.PrimitiveEntry<Supplier<? extends MinecraftPacket>> entry
                    : packetIdToSupplier.entries()) {
                MinecraftPacket packet = entry.value().get();
                if (packet.getClass().equals(originalClass)) {
                    entry.setValue(supplierRedirect);
                    redirected++;
                }
            }
        }
        return redirected;
    }

    private Collection<?> getProtocolRegistries(StateRegistry.PacketRegistry bound)
            throws NoSuchFieldException, IllegalAccessException {
        return getProtocolRegistriesMap(bound).values();
    }

    private Map<?, ?> getProtocolRegistriesMap(StateRegistry.PacketRegistry bound)
            throws NoSuchFieldException, IllegalAccessException {
        Field versionsField = requireField(
                StateRegistry.PacketRegistry.class,
                "versions",
                Map.class
        );
        return (Map<?, ?>) versionsField.get(bound);
    }

    StateRegistry.PacketMapping createPacketMapping(
            int id,
            ProtocolVersion protocolVersion,
            ProtocolVersion lastValidProtocolVersion,
            boolean packetDecoding
    ) throws NoSuchMethodException, InvocationTargetException,
            InstantiationException, IllegalAccessException {
        Constructor<StateRegistry.PacketMapping> constructor = ReflectUtil.handleAccessible(
                StateRegistry.PacketMapping.class.getDeclaredConstructor(
                        int.class,
                        ProtocolVersion.class,
                        ProtocolVersion.class,
                        boolean.class
                )
        );
        return constructor.newInstance(
                id,
                protocolVersion,
                lastValidProtocolVersion,
                packetDecoding
        );
    }

    StateRegistry.PacketMapping createPacketMapping(
            int id,
            ProtocolVersion protocolVersion,
            boolean packetDecoding
    ) throws NoSuchMethodException, InvocationTargetException,
            InstantiationException, IllegalAccessException {
        return createPacketMapping(id, protocolVersion, null, packetDecoding);
    }

    private <P extends MinecraftPacket> void registerPacket(
            StateRegistry.PacketRegistry packetRegistry,
            Class<P> clazz,
            Supplier<P> packetSupplier,
            StateRegistry.PacketMapping[] mappings
    ) throws NoSuchFieldException, IllegalAccessException {
        register(packetRegistry, clazz, packetSupplier, mappings);
    }

    @SuppressWarnings("unchecked")
    <P extends MinecraftPacket> void register(
            StateRegistry.PacketRegistry bound,
            Class<P> clazz,
            Supplier<P> packetSupplier,
            StateRegistry.PacketMapping... mappings
    ) throws NoSuchFieldException, IllegalAccessException {
        if (mappings.length == 0) {
            throw new IllegalArgumentException("At least one mapping must be provided.");
        }

        for (int i = 0; i < mappings.length; i++) {
            StateRegistry.PacketMapping current = mappings[i];
            StateRegistry.PacketMapping next =
                    i + 1 < mappings.length ? mappings[i + 1] : current;

            Field protocolVersionField = requireField(
                    StateRegistry.PacketMapping.class,
                    "protocolVersion",
                    ProtocolVersion.class
            );
            ProtocolVersion from = (ProtocolVersion) protocolVersionField.get(current);
            Field lastValidProtocolVersionField = requireField(
                    StateRegistry.PacketMapping.class,
                    "lastValidProtocolVersion",
                    ProtocolVersion.class
            );
            ProtocolVersion lastValid =
                    (ProtocolVersion) lastValidProtocolVersionField.get(current);
            if (lastValid != null) {
                if (next != current) {
                    throw new IllegalArgumentException(
                            "Cannot add a mapping after last valid mapping"
                    );
                }
                if (from.greaterThan(lastValid)) {
                    throw new IllegalArgumentException(
                            "Last mapping version cannot be higher than highest mapping version"
                    );
                }
            }

            ProtocolVersion to = current == next
                    ? lastValid != null ? lastValid : getLast(SUPPORTED_VERSIONS)
                    : (ProtocolVersion) protocolVersionField.get(next);
            ProtocolVersion lastInList =
                    lastValid != null ? lastValid : getLast(SUPPORTED_VERSIONS);
            if (from.noLessThan(to) && from != lastInList) {
                throw new IllegalArgumentException(String.format(
                        "Next mapping version (%s) should be lower then current (%s)",
                        to,
                        from
                ));
            }

            for (ProtocolVersion protocol : EnumSet.range(from, to)) {
                if (protocol == to && next != current) {
                    break;
                }
                StateRegistry.PacketRegistry.ProtocolRegistry registry =
                        (StateRegistry.PacketRegistry.ProtocolRegistry)
                                getProtocolRegistriesMap(bound).get(protocol);
                if (registry == null) {
                    throw new IllegalArgumentException(
                            "Unknown protocol version " + protocol
                    );
                }
                registerForProtocol(registry, current, clazz, packetSupplier);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <P extends MinecraftPacket> void registerForProtocol(
            StateRegistry.PacketRegistry.ProtocolRegistry registry,
            StateRegistry.PacketMapping mapping,
            Class<P> clazz,
            Supplier<P> packetSupplier
    ) throws NoSuchFieldException, IllegalAccessException {
        Field packetIdToSupplierField = requireField(
                StateRegistry.PacketRegistry.ProtocolRegistry.class,
                "packetIdToSupplier",
                IntObjectMap.class
        );
        IntObjectMap<Supplier<? extends MinecraftPacket>> packetIdToSupplier =
                (IntObjectMap<Supplier<? extends MinecraftPacket>>)
                        packetIdToSupplierField.get(registry);

        Field packetClassToIdField = requireField(
                StateRegistry.PacketRegistry.ProtocolRegistry.class,
                "packetClassToId",
                Map.class
        );
        Map<Class<? extends MinecraftPacket>, Integer> packetClassToId =
                (Map<Class<? extends MinecraftPacket>, Integer>)
                        packetClassToIdField.get(registry);

        int packetId = requireField(
                StateRegistry.PacketMapping.class,
                "id",
                int.class
        ).getInt(mapping);

        Integer registeredId = packetClassToId.get(clazz);
        if (registeredId != null && registeredId != packetId) {
            throw new IllegalArgumentException(
                    clazz.getSimpleName() + " is already registered with id "
                            + registeredId + " for version " + registry.version
            );
        }

        Supplier<? extends MinecraftPacket> registeredSupplier =
                packetIdToSupplier.get(packetId);
        if (registeredSupplier != null) {
            MinecraftPacket registeredPacket = registeredSupplier.get();
            if (!clazz.isInstance(registeredPacket)) {
                throw new IllegalArgumentException(
                        "Packet id " + packetId + " is already registered to "
                                + registeredPacket.getClass().getSimpleName()
                                + " for version " + registry.version
                );
            }
            packetClassToId.put(clazz, packetId);
            return;
        }

        boolean encodeOnly = requireField(
                StateRegistry.PacketMapping.class,
                "encodeOnly",
                boolean.class
        ).getBoolean(mapping);
        if (!encodeOnly) {
            packetIdToSupplier.put(packetId, packetSupplier);
        }
        packetClassToId.put(clazz, packetId);
    }

    private static Field requireField(
            Class<?> owner,
            String name,
            Class<?> expectedType
    ) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (!expectedType.isAssignableFrom(field.getType())) {
            throw new NoSuchFieldException(
                    owner.getName() + "." + name + " has type "
                            + field.getType().getName() + ", expected "
                            + expectedType.getName()
            );
        }
        return ReflectUtil.handleAccessible(field);
    }
}
