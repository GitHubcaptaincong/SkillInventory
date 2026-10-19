package internal.skills.config;



import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.MySQLContainer;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class MigrationTest {
    @Test void upgradePreservesExistingDraftSnapshotIdAndVersion() throws Exception {
        try(var mysql=new MySQLContainer<>("mysql:8.4.6").withDatabaseName("upgrade_test")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()).target("1").load().migrate();
            try(var c=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());var st=c.createStatement()) {
                st.executeUpdate("INSERT INTO skill(id,name,description,content,published_snapshot,lock_version,created_at,updated_at,published_at) VALUES(123,'legacy','draft','body',JSON_OBJECT('name','legacy','description','published','content','old body','publishedVersion',7,'publishedAt','2026-10-01T00:00:00Z'),9,NOW(),NOW(),NOW())");
                String before;
                try(var r=st.executeQuery("SELECT CONCAT(id,description,content,published_snapshot,lock_version) FROM skill WHERE id=123")){r.next();before=r.getString(1);}
                Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()).load().migrate();
                try(var r=st.executeQuery("SELECT CONCAT(id,description,content,published_snapshot,lock_version) FROM skill WHERE id=123")){r.next();assertThat(r.getString(1)).isEqualTo(before);}
                var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
                var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
                var initializer=new SecurityConfig().initialize(jdbc,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4),"concurrent-admin","isolated-concurrent-password");
                var gate=new java.util.concurrent.CyclicBarrier(2);
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                    for(int i=0;i<2;i++) tasks.add(pool.submit(() -> {gate.await();initializer.run(null);return null;}));
                    for(var task:tasks) task.get(15,java.util.concurrent.TimeUnit.SECONDS);
                }
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM administrator",Integer.class)).isEqualTo(1);
            }
        }
    }
}
