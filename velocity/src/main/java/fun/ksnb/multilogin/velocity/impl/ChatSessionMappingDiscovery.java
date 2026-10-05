package fun.ksnb.multilogin.velocity.impl;

import java.util.TreeMap;
import moe.caa.multilogin.api.MapperConfigAPI;
import moe.caa.multilogin.api.internal.injector.Injector;
import moe.caa.multilogin.api.internal.logger.LoggerProvider;
import moe.caa.multilogin.api.internal.main.MultiCoreAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Coordinates discovery with mapper reload/save and verified packet registration. */
public final class ChatSessionMappingDiscovery {
    private final MultiCoreAPI core;
    private final Injector injector;

    public ChatSessionMappingDiscovery(MultiCoreAPI core, Injector injector) {
        this.core = core;
        this.injector = injector;
    }

    public synchronized void handle(NewChatSessionPacketIDEvent event) {
        if (!event.getPlayer().isActive()) {
            return;
        }
        boolean reconnect;
        MapperConfigAPI mapper = core.getMapperConfig();
        synchronized (mapper) {
            int protocol = event.getVersion().getProtocol();
            int packetId = event.getPacketID();
            var mapping = mapper.getPacketMapping();
            var previous = new TreeMap<>(mapping);
            Integer oldId = mapping.get(protocol);
            String[] stage = {"validation"};
            try {
                if (protocol < 761 || event.getVersion().isUnknown()
                        || !event.getVersion().isSupported() || packetId < 0) {
                    throw new IllegalArgumentException("Unsupported ChatSession mapping");
                }
                boolean installed = injector.isChatSessionRegistered(protocol, packetId);
                if (installed && Integer.valueOf(packetId).equals(oldId)) {
                    return;
                }
                mapping.put(protocol, packetId);
                stage[0] = "registration";
                injector.registerChatSession(new TreeMap<>(mapping), () -> {
                    stage[0] = "verification";
                    if (!injector.isChatSessionRegistered(protocol, packetId)) {
                        throw new IllegalStateException("ChatSession decoder was not installed");
                    }
                    stage[0] = "save";
                    mapper.save();
                });
                LoggerProvider.getLogger().info(
                        "ChatSession mapping installed: protocol=" + protocol
                                + " packetId=" + packetId + " previous=" + oldId
                                + " resulting=" + mapping.get(protocol)
                );
                reconnect = !installed;
            } catch (Exception e) {
                mapping.clear();
                mapping.putAll(previous);
                LoggerProvider.getLogger().error(
                        "ChatSession mapping failed: protocol=" + protocol + " packetId=" + packetId
                                + " previous=" + oldId + " resulting=" + mapping.get(protocol)
                                + " stage=" + stage[0] + "; check mapper.yml and restart the proxy",
                        e
                );
                if (event.getPlayer().isActive()) {
                    disconnect(event, "chat_session_mapping_failed_msg");
                }
                return;
            }
        }
        if (reconnect && event.getPlayer().isActive()) {
            disconnect(event, "reconnect_msg");
        }
    }

    private void disconnect(NewChatSessionPacketIDEvent event, String messageKey) {
        event.getPlayer().disconnect(LegacyComponentSerializer.legacySection().deserialize(
                core.getLanguageHandler().getMessage(messageKey)
        ));
    }
}
