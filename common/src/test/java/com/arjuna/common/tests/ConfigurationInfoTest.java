package com.arjuna.common.tests;

import com.arjuna.common.util.ConfigurationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConfigurationInfoTest {

    @BeforeEach
    public void setUp() {
    }

    @Test
    public void testBuildId() {
        String buildId = System.getProperty("test_build_id");
        assertEquals(buildId, ConfigurationInfo.getBuildId());
    }

    @Test
    public void testPropertyFileName() {
        String propFileName = System.getProperty("test_build_prop_file");
        assertEquals(propFileName, ConfigurationInfo.getPropertiesFile());
    }

    @Test
    public void testVersion() {
        String version = System.getProperty("test_build_version");
        assertEquals(version, ConfigurationInfo.getVersion());
    }
}
