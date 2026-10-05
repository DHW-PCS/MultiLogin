package moe.caa.multilogin.core.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class MapperConfigTest {
    @TempDir Path directory;

    @Test
    void oldMappingDoesNotEraseNewerDefaultAndRoundTrips() throws Exception {
        Files.writeString(directory.resolve("mapper.yml"), "note: keep\nmapper:\n  775: '0x0A'\n");
        MapperConfig mapper = new MapperConfig(directory.toFile());
        mapper.reload();
        assertEquals(0x0A, mapper.getPacketMapping().get(775));
        assertEquals(0x0A, mapper.getPacketMapping().get(776));
        var expected = new TreeMap<>(mapper.getPacketMapping());
        mapper.save();
        MapperConfig fresh = new MapperConfig(directory.toFile());
        fresh.reload();
        assertEquals(expected, fresh.getPacketMapping());
        assertEquals("keep", YamlConfigurationLoader.builder()
                .path(directory.resolve("mapper.yml")).build().load().node("note").getString());
    }

    @Test
    void sameProtocolOverrideAndLowProtocolFilteringRemainSupported() throws Exception {
        Files.writeString(directory.resolve("mapper.yml"), "mapper:\n  760: '0x01'\n  776: '0x30'\n");
        MapperConfig mapper = new MapperConfig(directory.toFile());
        mapper.reload();
        assertFalse(mapper.getPacketMapping().containsKey(760));
        assertEquals(0x30, mapper.getPacketMapping().get(776));
        mapper.getPacketMapping().put(760, 1);
        assertFalse(mapper.getPacketMapping().containsKey(760));
    }

    @Test
    void saveRemovesStaleYamlEntries() throws Exception {
        Files.writeString(directory.resolve("mapper.yml"), "mapper:\n  775: '0x0A'\n");
        MapperConfig mapper = new MapperConfig(directory.toFile());
        mapper.reload();
        mapper.getPacketMapping().remove(775);
        mapper.save();
        mapper.reload();
        assertFalse(mapper.getPacketMapping().containsKey(775));
        assertTrue(YamlConfigurationLoader.builder().path(directory.resolve("mapper.yml"))
                .build().load().node("mapper", 775).virtual());
    }

    @Test
    void reloadReadsExternalEditAndReusesMapperWithoutSavingOldState() throws Exception {
        Path file = directory.resolve("mapper.yml");
        Files.writeString(file, "mapper:\n  775: '0x0A'\n");
        PluginConfig config = new PluginConfig(directory.toFile(), null);
        config.reloadMapper();
        MapperConfig original = config.getMapperConfig();
        original.getPacketMapping().put(774, 0x30);
        String edited = "mapper:\n  776: '0x31'\n";
        Files.writeString(file, edited);
        config.reloadMapper();
        assertSame(original, config.getMapperConfig());
        assertEquals(edited, Files.readString(file));
        assertFalse(original.getPacketMapping().containsKey(774));
        assertFalse(original.getPacketMapping().containsKey(775));
        assertEquals(0x31, original.getPacketMapping().get(776));
    }

    @Test
    void invalidReloadAndSaveKeepExistingMemoryAndFile() throws Exception {
        MapperConfig mapper = new MapperConfig(directory.toFile());
        var original = new TreeMap<>(mapper.getPacketMapping());
        String malformed = "mapper:\n  775: '0x0A'\n  776: invalid\n";
        Path file = directory.resolve("mapper.yml");
        Files.writeString(file, malformed);
        assertThrows(NumberFormatException.class, mapper::reload);
        assertEquals(original, mapper.getPacketMapping());
        String invalidYaml = "mapper: [unterminated\n";
        Files.writeString(file, invalidYaml);
        assertThrows(IllegalStateException.class, mapper::save);
        assertEquals(invalidYaml, Files.readString(file));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}
