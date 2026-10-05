package moe.caa.multilogin.core.configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;
import lombok.ToString;
import moe.caa.multilogin.api.MapperConfigAPI;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * ChatSessionBlocker 数据包映射配置。读写映射时应持有此配置对象的锁。
 */
@Getter
@ToString
public class MapperConfig implements MapperConfigAPI {
    private final TreeMap<Integer, Integer> packetMapping = new TreeMap<>() {
        @Override
        public Integer put(Integer key, Integer value) {
            return key < 761 ? value : super.put(key, value);
        }
    };
    private final File dataFolder;

    MapperConfig(File dataFolder) {
        this.dataFolder = dataFolder;
        packetMapping.putAll(defaultMappings());
    }

    private static TreeMap<Integer, Integer> defaultMappings() {
        return new TreeMap<>(Map.of(
                761, 0x20, 762, 0x06, 765, 0x07,
                768, 0x08, 771, 0x09, 776, 0x0A
        ));
    }

    @Override
    public synchronized void save() {
        Path temporaryFile = null;
        try {
            Path target = dataFolder.toPath().resolve("mapper.yml");
            CommentedConfigurationNode root = YamlConfigurationLoader.builder()
                    .path(target).indent(2).build().load();
            CommentedConfigurationNode mapper = root.node("mapper");
            for (Object key : new ArrayList<>(mapper.childrenMap().keySet())) {
                mapper.removeChild(key);
            }
            for (Map.Entry<Integer, Integer> entry : packetMapping.entrySet()) {
                mapper.node(entry.getKey()).set(String.format("0x%02X", entry.getValue()));
            }
            temporaryFile = Files.createTempFile(dataFolder.toPath(), "mapper-", ".tmp");
            YamlConfigurationLoader.builder().path(temporaryFile).indent(2).build().save(root);
            Files.move(temporaryFile, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save mapper.yml atomically", e);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The original mapper remains intact if replacement failed.
                }
            }
        }
    }

    @Override
    public synchronized void reload() {
        try {
            ConfigurationNode mapper = YamlConfigurationLoader.builder()
                    .file(new File(dataFolder, "mapper.yml")).build().load().node("mapper");
            TreeMap<Integer, Integer> candidate = defaultMappings();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : mapper.childrenMap().entrySet()) {
                int protocol = Integer.parseInt(entry.getKey().toString());
                String value = entry.getValue().getString();
                if (value != null && protocol >= 761) {
                    int packetId = Integer.decode(value);
                    if (packetId < 0) {
                        throw new IllegalArgumentException("Negative packet id for protocol " + protocol);
                    }
                    candidate.put(protocol, packetId);
                }
            }
            packetMapping.clear();
            packetMapping.putAll(candidate);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to reload mapper.yml", e);
        }
    }
}
