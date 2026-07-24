package fun.ksnb.multilogin.velocity.impl;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

public class ChatSessionHandler extends ChannelDuplexHandler {
    private final Player player;
    private final EventManager eventManager;
    public ChatSessionHandler(Player player, EventManager eventManager) {
        this.player = player;
        this.eventManager = eventManager;
    }

    @Override
    public void channelRead(
            final @NotNull ChannelHandlerContext ctx,
            final @NotNull Object packet
    ) throws Exception {
        if (packet instanceof ByteBuf buffer) {
            findPlayerSessionPacketId(buffer, player.getProtocolVersion()).ifPresent(packetId ->
                    eventManager.fire(new NewChatSessionPacketIDEvent(
                            packetId,
                            player.getProtocolVersion(),
                            player
                    ))
            );
        }
        super.channelRead(ctx, packet);
    }

    static OptionalInt findPlayerSessionPacketId(ByteBuf buffer, com.velocitypowered.api.network.ProtocolVersion version) {
        ByteBuf candidate = buffer.asReadOnly();
        try {
            int packetId = ProtocolUtils.readVarInt(candidate);
            ProtocolUtils.readUuid(candidate);
            ProtocolUtils.readPlayerKey(version, candidate);
            return candidate.isReadable() ? OptionalInt.empty() : OptionalInt.of(packetId);
        } catch (Throwable ignored) {
            return OptionalInt.empty();
        }
    }
}
