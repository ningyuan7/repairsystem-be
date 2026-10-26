package com.gaofeng.repair;

import com.gaofeng.repair.bootstrap.RepairSharedConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairMigrationSafetyTest {
    @Test void refusesLegacyDatabaseBeforeExecutingMigration() throws Exception {
        Flyway flyway=flyway("zhiguang");
        assertThatThrownBy(()->new RepairSharedConfiguration().isolatedRepairMigration().migrate(flyway))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("legacy databases are prohibited");
        verify(flyway,never()).migrate();
    }
    @Test void permitsExplicitIsolatedRepairDatabase() throws Exception {
        Flyway flyway=flyway("repair_system");
        new RepairSharedConfiguration().isolatedRepairMigration().migrate(flyway);
        verify(flyway).migrate();
    }
    private Flyway flyway(String database) throws Exception {
        var flyway=mock(Flyway.class); var config=mock(Configuration.class); var source=mock(DataSource.class); var connection=mock(Connection.class);
        when(flyway.getConfiguration()).thenReturn(config); when(config.getDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection); when(connection.getCatalog()).thenReturn(database);
        return flyway;
    }
}
