package io.amient.affinity.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.amient.affinity.core.util.TimeCryptoProof;
import io.amient.affinity.core.util.TimeCryptoProofSHA256;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Arrays;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CfgTest {

    public static NodeConfig NodeConfig = new NodeConfig() {
        @Override
        public NodeConfig apply(Config config) throws IllegalArgumentException {
            return new NodeConfig().apply(config);
        }
    };

    public static class NodeConfig extends CfgStruct<NodeConfig> {
        private CfgLong StartupTimeoutMs = longint("startup.timeout.ms", true);
        private CfgLong ShutdownTimeoutMs = longint("shutdown.timeout.ms", true);
        private CfgGroup<ServiceConfig> Services = group("service", ServiceConfig.class, false);

    }

    public static class ServiceConfig extends CfgStruct<ServiceConfig> {
        private CfgCls<TimeCryptoProof> Class = cls("class", TimeCryptoProof.class, true);
        private CfgList IntList = list("intlist", CfgInt.class, false);
        private CfgGroup IntLists = group("lists", CfgIntList.class, false);
        private Cfg Undefined = string("undefined", false);
        private CfgGroup UndefinedGroup = group("undefined-group", UndefinedGroupConfig.class, false);
    }

    public static class UndefinedGroupConfig extends CfgStruct<UndefinedGroupConfig> {
        public UndefinedGroupConfig() {
            super(Options.IGNORE_UNKNOWN);
        }
    }

    @Rule
    public ExpectedException ex = ExpectedException.none();

    @Test
    public void reportMissingPropertiesOnlyIfRequired() {
        ex.expect(IllegalArgumentException.class);
        ex.expectMessage("startup.timeout.ms is required\n" +
                "shutdown.timeout.ms is required\n");
        NodeConfig.apply(ConfigFactory.empty());
    }

    @Test
    public void reportInvalidPropertyForDifferentStruct() {
        ex.expect(IllegalArgumentException.class);
        ex.expectMessage("class is required in service.wrongstruct\n" +
                "something.we.dont.recognize is not a known property of service.wrongstruct\n");
        Config config = ConfigFactory.parseMap(new HashMap<String, Object>() {{
            put(NodeConfig.StartupTimeoutMs.path(), 100L);
            put(NodeConfig.ShutdownTimeoutMs.path(), 1000L);
            put(NodeConfig.Services.apply("wrongstruct").path("something.we.dont.recognize"), 20);
        }});
        NodeConfig.apply(config);
    }

    @Test
    public void reportRecoginzieCorrectGroupType() {
        ex.expect(IllegalArgumentException.class);
        ex.expectMessage("com.typesafe.config.Config is not an instance of class io.amient.affinity.core.util.TimeCryptoProof\n");
        Config config = ConfigFactory.parseMap(new HashMap<String, Object>() {{
            put(NodeConfig.StartupTimeoutMs.path(), 100L);
            put(NodeConfig.ShutdownTimeoutMs.path(), 1000L);
            put(NodeConfig.Services.apply("wrongclass").Class.path(), Config.class.getName());
        }});
        NodeConfig.apply(config);
    }

    @Test
    public void recognizeCorrectlyConfigured() {
        Config config = ConfigFactory.parseMap(new HashMap<String, Object>() {{
            put(NodeConfig.StartupTimeoutMs.path(), 100L);
            put(NodeConfig.ShutdownTimeoutMs.path(), 1000L);
            put(NodeConfig.Services.apply("myservice").Class.path(), TimeCryptoProofSHA256.class.getName());
            put(NodeConfig.Services.apply("myservice").IntList.path(), Arrays.asList(1, 2, 3));
            put(NodeConfig.Services.apply("myservice").IntLists.apply("group1").path(), Arrays.asList(1, 2, 3));
            put(NodeConfig.Services.apply("myservice").IntLists.apply("group2").path(), Arrays.asList(4));
            put(NodeConfig.Services.apply("myservice").UndefinedGroup.path("some.group.member.attribute"), "x");
        }});

        NodeConfig applied = NodeConfig.apply(config);
        assertEquals(TimeCryptoProofSHA256.class, applied.Services.apply("myservice").Class.apply());
        assertEquals(Arrays.asList(1, 2, 3), applied.Services.apply("myservice").IntList.apply());
        assertEquals(Arrays.asList(1, 2, 3), applied.Services.apply("myservice").IntLists.apply("group1").apply());
        assertEquals(Arrays.asList(4), applied.Services.apply("myservice").IntLists.apply("group2").apply());
        assertTrue(applied.Services.isDefined());
        assertTrue(applied.Services.apply("myservice").Class.isDefined());
        assertTrue(applied.Services.apply("myservice").IntList.isDefined());
        assertFalse(applied.Services.apply("myservice").Undefined.isDefined());
    }

}
