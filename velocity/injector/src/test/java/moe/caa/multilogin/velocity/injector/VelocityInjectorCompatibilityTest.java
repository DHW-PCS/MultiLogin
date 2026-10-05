package moe.caa.multilogin.velocity.injector;

import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.SERVERBOUND;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket;
import java.util.Map;
import moe.caa.multilogin.velocity.injector.redirect.chat.PlayerSessionPacketBlocker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

class VelocityInjectorCompatibilityTest {
    private PacketRegistrySnapshot snapshot;

    @BeforeEach
    void snapshotRegistries() throws Exception {
        snapshot = new PacketRegistrySnapshot();
    }

    @AfterEach
    void restoreRegistries() {
        snapshot.close();
    }


    @Test
    void velocity351InternalContractIsAvailable() {
        assertDoesNotThrow(() -> new VelocityInjector().validateCompatibility());
    }

    @Test
    void loginPacketRegistryUsesNettyPrimitiveMap() throws Exception {
        VelocityInjector injector = new VelocityInjector();
        StateRegistry.PacketRegistry registry =
                injector.getServerboundPacketRegistry(StateRegistry.LOGIN);

        int redirected = injector.redirectInput(
                registry,
                EncryptionResponsePacket.class,
                TestEncryptionResponsePacket::new
        );
        try {
            assertTrue(redirected > 0);
        } finally {
            injector.redirectInput(
                    registry,
                    TestEncryptionResponsePacket.class,
                    EncryptionResponsePacket::new
            );
        }
    }

    @Test
    void chatPacketRegistrationIsIdempotentAndRejectsConflicts() throws Exception {
        VelocityInjector injector = new VelocityInjector();
        StateRegistry.PacketRegistry registry =
                injector.getServerboundPacketRegistry(StateRegistry.PLAY);
        StateRegistry.PacketRegistry.ProtocolRegistry protocolRegistry =
                StateRegistry.PLAY.getProtocolRegistry(
                        SERVERBOUND,
                        ProtocolVersion.MINECRAFT_26_2
                );
        int unusedPacketId = findUnusedPacketId(protocolRegistry);
        StateRegistry.PacketMapping mapping = injector.createPacketMapping(
                unusedPacketId,
                ProtocolVersion.MINECRAFT_26_2,
                ProtocolVersion.MINECRAFT_26_2,
                false
        );

        injector.register(
                registry,
                TestChatPacket.class,
                TestChatPacket::new,
                mapping
        );
        assertDoesNotThrow(() -> injector.register(
                registry,
                TestChatPacket.class,
                TestChatPacket::new,
                mapping
        ));
        assertInstanceOf(
                TestChatPacket.class,
                protocolRegistry.createPacket(unusedPacketId)
        );
        assertThrows(IllegalArgumentException.class, () -> injector.register(
                registry,
                EncryptionResponsePacket.class,
                EncryptionResponsePacket::new,
                mapping
        ));
    }

    @Test
    void lastConfiguredChatMappingDoesNotExtendIntoNewerProtocols() {
        VelocityInjector injector = new VelocityInjector();

        injector.registerChatSession(Map.of(771, 0x09));

        assertInstanceOf(
                SessionPlayerChatPacket.class,
                StateRegistry.PLAY.getProtocolRegistry(
                        SERVERBOUND,
                        ProtocolVersion.MINECRAFT_26_2
                ).createPacket(0x09)
        );
    }

    @Test
    void inheritedChatMappingStopsAtProtocolConflictBefore262Mapping() {
        VelocityInjector injector = new VelocityInjector();

        injector.registerChatSession(Map.of(771, 0x09, 776, 0x0A));

        assertInstanceOf(
                SessionPlayerChatPacket.class,
                StateRegistry.PLAY.getProtocolRegistry(
                        SERVERBOUND,
                        ProtocolVersion.getProtocolVersion(775)
                ).createPacket(0x09)
        );
        assertInstanceOf(
                PlayerSessionPacketBlocker.class,
                StateRegistry.PLAY.getProtocolRegistry(
                        SERVERBOUND,
                        ProtocolVersion.MINECRAFT_26_2
                ).createPacket(0x0A)
        );
    }

    @Test
    void failedCompletionRollsBackOnlyThisBatch() throws Exception {
        VelocityInjector injector = new VelocityInjector();
        StateRegistry.PacketRegistry registry = injector.getServerboundPacketRegistry(StateRegistry.PLAY);
        var version = ProtocolVersion.getProtocolVersion(775);
        var protocolRegistry = StateRegistry.PLAY.getProtocolRegistry(SERVERBOUND, version);
        int unrelatedId = findUnusedPacketId(protocolRegistry);
        var unrelatedMapping = injector.createPacketMapping(unrelatedId, version, version, false);
        assertThrows(IllegalStateException.class, () -> injector.registerChatSession(Map.of(775, 0x0A), () -> {
            try {
                injector.register(registry, TestChatPacket.class, TestChatPacket::new, unrelatedMapping);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("synthetic completion failure");
        }));
        assertFalse(injector.isChatSessionRegistered(775, 0x0A));
        assertInstanceOf(TestChatPacket.class, protocolRegistry.createPacket(unrelatedId));
    }

    private static int findUnusedPacketId(
            StateRegistry.PacketRegistry.ProtocolRegistry registry
    ) {
        for (int packetId = 0x40; packetId <= 0x7f; packetId++) {
            if (registry.createPacket(packetId) == null) {
                return packetId;
            }
        }
        throw new IllegalStateException("No unused packet id available for compatibility test");
    }

    private static final class TestEncryptionResponsePacket
            extends EncryptionResponsePacket {
    }

    private static final class TestChatPacket
            extends PlayerSessionPacketBlocker {
    }
}
