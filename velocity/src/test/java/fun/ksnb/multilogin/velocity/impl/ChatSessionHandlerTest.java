package fun.ksnb.multilogin.velocity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

        OptionalInt packetId = ChatSessionHandler.findPlayerSessionPacketId(
                buffer,
                ProtocolVersion.MINECRAFT_26_2
        );

        assertTrue(packetId.isPresent());
        assertEquals(300, packetId.getAsInt());
        assertEquals(0, buffer.readerIndex());
    }

    @Test
    void rejectsTrailingAndMalformedPayloads() throws Exception {
        ByteBuf trailing = playerSessionPacket(9);
        trailing.writeByte(1);
        ByteBuf truncated = Unpooled.buffer();
        ProtocolUtils.writeVarInt(truncated, 9);
        ProtocolUtils.writeUuid(truncated, UUID.randomUUID());

        assertTrue(ChatSessionHandler.findPlayerSessionPacketId(
                trailing,
                ProtocolVersion.MINECRAFT_26_2
        ).isEmpty());
        assertTrue(ChatSessionHandler.findPlayerSessionPacketId(
                truncated,
                ProtocolVersion.MINECRAFT_26_2
        ).isEmpty());
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
