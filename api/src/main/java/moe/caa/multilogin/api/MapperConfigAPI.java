package moe.caa.multilogin.api;

import java.util.Map;

public interface MapperConfigAPI {
    /** Hold this configuration object's monitor when reading or changing the mutable map. */
    Map<Integer,Integer> getPacketMapping();
    void save();
    void reload();
}
