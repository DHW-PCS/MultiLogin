package fun.ksnb.multilogin.velocity.main;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.velocitypowered.proxy.network.Connections;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class MultiLoginVelocityPipelineTest {

    @Test
    void installAndRemoveAreIdempotent() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(Connections.HANDLER, new ChannelDuplexHandler());
        ChannelDuplexHandler first = new ChannelDuplexHandler();

        MultiLoginVelocity.installChatSessionHandler(channel, first);
        channel.runPendingTasks();
        MultiLoginVelocity.installChatSessionHandler(channel, new ChannelDuplexHandler());
        channel.runPendingTasks();

        assertSame(first, channel.pipeline().get("MultiLoginChatSession"));
        MultiLoginVelocity.removeChatSessionHandler(channel);
        channel.runPendingTasks();
        assertDoesNotThrow(() -> {
            MultiLoginVelocity.removeChatSessionHandler(channel);
            channel.runPendingTasks();
        });
        channel.finishAndReleaseAll();
    }
}
