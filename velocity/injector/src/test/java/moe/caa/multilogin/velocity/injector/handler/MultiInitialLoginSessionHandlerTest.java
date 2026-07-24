package moe.caa.multilogin.velocity.injector.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.AuthSessionHandler;
import com.velocitypowered.proxy.connection.client.LoginInboundConnection;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MultiInitialLoginSessionHandlerTest {

    @Test
    void passesAuthenticationServerIdHashToVelocityHandler() throws Throwable {
        MultiInitialLoginSessionHandler.init();
        String serverIdHash = "same-hash-used-for-hasJoined";
        AuthSessionHandler handler =
                MultiInitialLoginSessionHandler.createAuthSessionHandler(
                        mock(VelocityServer.class),
                        mock(LoginInboundConnection.class),
                        new GameProfile(UUID.randomUUID(), "player", List.of()),
                        serverIdHash
                );
        Field field = AuthSessionHandler.class.getDeclaredField("serverIdHash");
        field.setAccessible(true);

        assertEquals(serverIdHash, field.get(handler));
    }
}
