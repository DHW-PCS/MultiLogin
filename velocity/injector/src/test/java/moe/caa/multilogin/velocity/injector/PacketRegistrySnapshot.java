package moe.caa.multilogin.velocity.injector;

import com.velocitypowered.proxy.protocol.StateRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Restores Velocity's global registries so tests cannot depend on execution order. */
public final class PacketRegistrySnapshot implements AutoCloseable {
    private final List<Runnable> restorations = new ArrayList<>();

    public PacketRegistrySnapshot() throws Exception {
        for (StateRegistry state : new StateRegistry[]{StateRegistry.PLAY, StateRegistry.LOGIN}) {
            Object bound = field(StateRegistry.class, "serverbound").get(state);
            Map<?, ?> versions = (Map<?, ?>) field(bound.getClass(), "versions").get(bound);
            for (Object registry : versions.values()) {
                capture(registry, "packetIdToSupplier");
                capture(registry, "packetClassToId");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void capture(Object registry, String name) throws Exception {
        Map<Object, Object> live = (Map<Object, Object>) field(registry.getClass(), name).get(registry);
        Map<Object, Object> copy = new HashMap<>(live);
        restorations.add(() -> {
            live.clear();
            live.putAll(copy);
        });
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Override
    public void close() {
        restorations.forEach(Runnable::run);
    }
}
