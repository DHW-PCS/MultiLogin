package moe.caa.multilogin.core.configuration;

import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.SERVERBOUND;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket;
import fun.ksnb.multilogin.velocity.impl.ChatSessionMappingDiscovery;
import fun.ksnb.multilogin.velocity.impl.NewChatSessionPacketIDEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import moe.caa.multilogin.api.internal.injector.Injector;
import moe.caa.multilogin.api.internal.language.LanguageAPI;
import moe.caa.multilogin.api.internal.logger.Logger;
import moe.caa.multilogin.api.internal.logger.LoggerProvider;
import moe.caa.multilogin.api.internal.main.MultiCoreAPI;
import moe.caa.multilogin.velocity.injector.PacketRegistrySnapshot;
import moe.caa.multilogin.velocity.injector.VelocityInjector;
import moe.caa.multilogin.velocity.injector.redirect.chat.PlayerSessionPacketBlocker;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatSessionMappingIntegrationTest {
    @TempDir Path directory;
    private PacketRegistrySnapshot snapshot;
    private MapperConfig mapper;
    private VelocityInjector injector;
    private MultiCoreAPI core;
    private ChatSessionMappingDiscovery discovery;
    private Logger previousLogger;

    @BeforeEach
    void setup() throws Exception {
        snapshot = new PacketRegistrySnapshot();
        previousLogger = LoggerProvider.getLogger();
        LoggerProvider.setLogger(mock(Logger.class));
        Files.writeString(directory.resolve("mapper.yml"), "mapper: {}\n");
        mapper = spy(new MapperConfig(directory.toFile()));
        injector = new VelocityInjector();
        core = mock(MultiCoreAPI.class);
        LanguageAPI language = mock(LanguageAPI.class);
        when(core.getMapperConfig()).thenReturn(mapper);
        when(core.getLanguageHandler()).thenReturn(language);
        when(language.getMessage(eq("reconnect_msg"))).thenReturn("Reconnect");
        when(language.getMessage(eq("chat_session_mapping_failed_msg"))).thenReturn("Contact administrator");
        discovery = new ChatSessionMappingDiscovery(core, injector);
    }

    @AfterEach
    void cleanup() {
        snapshot.close();
        LoggerProvider.setLogger(previousLogger);
    }

    @Test
    void incidentMapperLoadsBothProtocolsAndDoesNotKickOnRedetection() throws Exception {
        Files.writeString(directory.resolve("mapper.yml"), "mapper:\n  775: '0x0A'\n");
        mapper.reload();
        injector.registerChatSession(mapper.getPacketMapping());
        assertTrue(injector.isChatSessionRegistered(775, 0x0A));
        assertTrue(injector.isChatSessionRegistered(776, 0x0A));
        Player player = player();
        discovery.handle(event(player, 776, 0x0A));
        verify(player, never()).disconnect(any(Component.class));
        mapper.save();
        MapperConfig fresh = new MapperConfig(directory.toFile());
        fresh.reload();
        injector.registerChatSession(fresh.getPacketMapping());
        assertEquals(0x0A, fresh.getPacketMapping().get(775));
        assertEquals(0x0A, fresh.getPacketMapping().get(776));
    }

    @Test
    void learnsOncePersistsAndDecodesOnReconnect() throws Exception {
        injector.registerChatSession(mapper.getPacketMapping());
        Player first = player();
        discovery.handle(event(first, 775, 0x0A));
        discovery.handle(event(first, 775, 0x0A));
        verify(first, times(1)).disconnect(Component.text("Reconnect"));
        verify(mapper, times(1)).save();
        assertTrue(injector.isChatSessionRegistered(775, 0x0A));
        mapper.reload();
        Player second = player();
        discovery.handle(event(second, 775, 0x0A));
        verify(second, never()).disconnect(any(Component.class));

        MinecraftDecoder decoder = new MinecraftDecoder(SERVERBOUND);
        decoder.setState(StateRegistry.PLAY);
        decoder.setProtocolVersion(ProtocolVersion.getProtocolVersion(775));
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        ByteBuf packet = sessionPacket(0x0A);
        try {
            assertTrue(channel.writeInbound(packet));
            assertInstanceOf(PlayerSessionPacketBlocker.class, channel.readInbound());
            assertEquals(0, packet.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void concurrentDiscoveryCommitsAndRequestsReconnectOnlyOnce() throws Exception {
        injector.registerChatSession(mapper.getPacketMapping());
        Player first = player();
        Player second = player();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> {
                await(start);
                discovery.handle(event(first, 775, 0x0A));
            }, executor);
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> {
                await(start);
                discovery.handle(event(second, 775, 0x0A));
            }, executor);
            start.countDown();
            CompletableFuture.allOf(one, two).get(10, TimeUnit.SECONDS);
        }
        verify(mapper, times(1)).save();
        long kicks = java.util.stream.Stream.of(first, second).flatMap(p ->
                mockingDetails(p).getInvocations().stream()).filter(i ->
                i.getMethod().getName().equals("disconnect")).count();
        assertEquals(1, kicks);
    }

    @Test
    void saveFailureRollsBackRegistryAndMemoryWithoutChangingFile() throws Exception {
        injector.registerChatSession(mapper.getPacketMapping());
        var before = new TreeMap<>(mapper.getPacketMapping());
        String fileBefore = Files.readString(directory.resolve("mapper.yml"));
        doThrow(new IllegalStateException("synthetic save failure")).when(mapper).save();
        Player player = player();
        discovery.handle(event(player, 775, 0x0A));
        assertEquals(before, mapper.getPacketMapping());
        assertFalse(injector.isChatSessionRegistered(775, 0x0A));
        assertTrue(injector.isChatSessionRegistered(776, 0x0A));
        assertEquals(fileBefore, Files.readString(directory.resolve("mapper.yml")));
        verify(player).disconnect(Component.text("Contact administrator"));
        verify(player, never()).disconnect(Component.text("Reconnect"));
        doCallRealMethod().when(mapper).save();
        Player retry = player();
        discovery.handle(event(retry, 775, 0x0A));
        assertTrue(injector.isChatSessionRegistered(775, 0x0A));
        verify(retry).disconnect(Component.text("Reconnect"));
    }

    @Test
    void conflictingPacketDoesNotInstallOtherEntriesOrSave() {
        var before = new TreeMap<>(mapper.getPacketMapping());
        Player player = player();
        discovery.handle(event(player, 775, 0x09));
        assertEquals(before, mapper.getPacketMapping());
        assertFalse(injector.isChatSessionRegistered(776, 0x0A));
        assertInstanceOf(SessionPlayerChatPacket.class, StateRegistry.PLAY.getProtocolRegistry(
                SERVERBOUND, ProtocolVersion.getProtocolVersion(775)).createPacket(0x09));
        verify(mapper, never()).save();
        verify(player).disconnect(Component.text("Contact administrator"));
    }

    @Test
    void doesNotChangeExistingBlockerId() {
        mapper.getPacketMapping().put(775, 0x0A);
        injector.registerChatSession(mapper.getPacketMapping());
        Player player = player();
        discovery.handle(event(player, 775, 0x70));
        assertEquals(0x0A, mapper.getPacketMapping().get(775));
        assertTrue(injector.isChatSessionRegistered(775, 0x0A));
        assertFalse(injector.isChatSessionRegistered(775, 0x70));
        verify(mapper, never()).save();
        verify(player).disconnect(Component.text("Contact administrator"));
    }

    @Test
    void ignoresFutureProtocolAndDoesNotInheritIntoIt() {
        injector.registerChatSession(Map.of(775, 0x0A, 777, 0x0A));
        assertTrue(injector.isChatSessionRegistered(775, 0x0A));
        assertFalse(injector.isChatSessionRegistered(776, 0x0A));
        assertFalse(injector.isChatSessionRegistered(777, 0x0A));
        Player player = player();
        discovery.handle(new NewChatSessionPacketIDEvent(0x0A, ProtocolVersion.UNKNOWN, player));
        verify(mapper, never()).save();
        verify(player).disconnect(Component.text("Contact administrator"));
    }

    @Test
    void verificationFailureCannotPersistOrRequestReconnect() {
        Injector broken = mock(Injector.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(broken).registerChatSession(anyMap(), any(Runnable.class));
        Player player = player();
        new ChatSessionMappingDiscovery(core, broken).handle(event(player, 775, 0x0A));
        assertFalse(mapper.getPacketMapping().containsKey(775));
        verify(mapper, never()).save();
        verify(player).disconnect(Component.text("Contact administrator"));
    }

    @Test
    void onlyDisconnectsOriginalConnectionAndSkipsInactiveConnections() {
        Player original = player();
        Player replacement = player();
        UUID uuid = UUID.randomUUID();
        when(original.getUniqueId()).thenReturn(uuid);
        when(replacement.getUniqueId()).thenReturn(uuid);
        when(original.isActive()).thenReturn(true, false);
        discovery.handle(event(original, 775, 0x0A));
        assertTrue(injector.isChatSessionRegistered(775, 0x0A));
        verify(original, never()).disconnect(any(Component.class));
        verify(replacement, never()).disconnect(any(Component.class));
        verify(core, never()).getPlugin();
        when(replacement.isActive()).thenReturn(false);
        discovery.handle(event(replacement, 774, 0x70));
        assertFalse(mapper.getPacketMapping().containsKey(774));
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.isActive()).thenReturn(true);
        return player;
    }

    private static NewChatSessionPacketIDEvent event(Player player, int protocol, int packetId) {
        return new NewChatSessionPacketIDEvent(packetId, ProtocolVersion.getProtocolVersion(protocol), player);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ByteBuf sessionPacket(int packetId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        IdentifiedKey key = new IdentifiedKeyImpl(IdentifiedKey.Revision.LINKED_V2,
                generator.generateKeyPair().getPublic(), Instant.now().plusSeconds(300), new byte[]{1});
        ByteBuf buffer = Unpooled.buffer();
        ProtocolUtils.writeVarInt(buffer, packetId);
        ProtocolUtils.writeUuid(buffer, UUID.randomUUID());
        ProtocolUtils.writePlayerKey(buffer, key);
        return buffer;
    }
}
