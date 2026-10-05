package fun.ksnb.multilogin.velocity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatSessionHandlerTest {

    @Test
    void readsVarIntPacketIdAndCompletePlayerSessionPayload() throws Exception {
        ByteBuf buffer = playerSessionPacket(300);

        try {
            OptionalInt packetId = ChatSessionHandler.findPlayerSessionPacketId(
                    buffer, ProtocolVersion.MINECRAFT_26_2
            );
            assertTrue(packetId.isPresent());
            assertEquals(300, packetId.getAsInt());
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsTrailingAndMalformedPayloads() throws Exception {
        ByteBuf trailing = playerSessionPacket(9);
        trailing.writeByte(1);
        ByteBuf truncated = Unpooled.buffer();
        ProtocolUtils.writeVarInt(truncated, 9);
        ProtocolUtils.writeUuid(truncated, UUID.randomUUID());

        try {
            assertTrue(ChatSessionHandler.findPlayerSessionPacketId(
                    trailing, ProtocolVersion.MINECRAFT_26_2
            ).isEmpty());
            assertTrue(ChatSessionHandler.findPlayerSessionPacketId(
                    truncated, ProtocolVersion.MINECRAFT_26_2
            ).isEmpty());
        } finally {
            trailing.release();
            truncated.release();
        }
    }

    @Test
    void consumesAndReleasesRepeatedPacketsWithOnlyOneDiscoveryEvent() throws Exception {
        Player player = mock(Player.class);
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_2);
        EventManager events = mock(EventManager.class);
        EmbeddedChannel channel = new EmbeddedChannel(new ChatSessionHandler(player, events));
        ByteBuf first = playerSessionPacket(10);
        ByteBuf second = playerSessionPacket(10);
        try {
            assertFalse(channel.writeInbound(first));
            assertFalse(channel.writeInbound(second));
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertNull(channel.readInbound());
            verify(events, times(1)).fire(any(NewChatSessionPacketIDEvent.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void passesNonmatchingDataThroughWithoutChangingReaderIndex() throws Exception {
        Player player = mock(Player.class);
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_2);
        EventManager events = mock(EventManager.class);
        EmbeddedChannel channel = new EmbeddedChannel(new ChatSessionHandler(player, events));
        ByteBuf trailing = playerSessionPacket(10).writeByte(1);
        try {
            assertTrue(channel.writeInbound(trailing));
            assertSame(trailing, channel.readInbound());
            assertEquals(0, trailing.readerIndex());
            assertEquals(1, trailing.refCnt());
            verifyNoInteractions(events);
        } finally {
            trailing.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void releasesDetectedBufferEvenWhenEventDispatchThrows() throws Exception {
        Player player = mock(Player.class);
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_2);
        EventManager events = mock(EventManager.class);
        when(events.fire(any(NewChatSessionPacketIDEvent.class)))
                .thenThrow(new IllegalStateException("synthetic dispatch failure"));
        EmbeddedChannel channel = new EmbeddedChannel(new ChatSessionHandler(player, events));
        ByteBuf buffer = playerSessionPacket(10);
        try {
            assertThrows(IllegalStateException.class, () -> channel.writeInbound(buffer));
            assertEquals(0, buffer.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ByteBuf playerSessionPacket(int packetId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        IdentifiedKey key = new IdentifiedKeyImpl(
                IdentifiedKey.Revision.LINKED_V2,
                keyPair.getPublic(),
                Instant.now().plusSeconds(300),
                new byte[]{1, 2, 3}
        );

        ByteBuf buffer = Unpooled.buffer();
        ProtocolUtils.writeVarInt(buffer, packetId);
        ProtocolUtils.writeUuid(buffer, UUID.randomUUID());
        ProtocolUtils.writePlayerKey(buffer, key);
        return buffer;
    }
}
