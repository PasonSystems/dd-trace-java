package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class PreparedStatementInstrumentation
    extends AbstractPreparedStatementInstrumentation {

  private static final String[] CONCRETE_TYPES = {
    // redshift
    "com.amazon.redshift.jdbc.RedshiftPreparedStatement",
    // jt400
    "com.ibm.as400.access.AS400JDBCPreparedStatement",
    // probably patchy cover
    "com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement",
    "com.microsoft.sqlserver.jdbc.SQLServerCallableStatement",
    // should cover mysql
    "com.mysql.jdbc.PreparedStatement",
    "com.mysql.jdbc.jdbc1.PreparedStatement",
    "com.mysql.jdbc.jdbc2.PreparedStatement",
    "com.mysql.jdbc.ServerPreparedStatement",
    "com.mysql.cj.jdbc.PreparedStatement",
    "com.mysql.cj.jdbc.ServerPreparedStatement",
    "com.mysql.cj.jdbc.ClientPreparedStatement",
    "com.mysql.cj.JdbcCallableStatement",
    "com.mysql.jdbc.CallableStatement",
    "com.mysql.jdbc.jdbc1.CallableStatement",
    "com.mysql.jdbc.jdbc2.CallableStatement",
    "com.mysql.cj.jdbc.CallableStatement",
    "oracle.jdbc.driver.OracleCallableStatement",
    "oracle.jdbc.driver.OraclePreparedStatement",
    // covers hsqldb
    "org.hsqldb.jdbc.JDBCPreparedStatement",
    "org.hsqldb.jdbc.jdbcPreparedStatement",
    "org.hsqldb.jdbc.JDBCCallableStatement",
    "org.hsqldb.jdbc.jdbcCallableStatement",
    // should cover derby
    "org.apache.derby.impl.jdbc.EmbedPreparedStatement",
    "org.apache.derby.impl.jdbc.EmbedCallableStatement",
    // hive
    "org.apache.hive.jdbc.HivePreparedStatement",
    "org.apache.hive.jdbc.HiveCallableStatement",
    "org.apache.phoenix.jdbc.PhoenixPreparedStatement",
    "org.apache.pinot.client.PinotPreparedStatement",
    // covers h2
    "org.h2.jdbc.JdbcPreparedStatement",
    "org.h2.jdbc.JdbcCallableStatement",
    // covers mariadb
    "org.mariadb.jdbc.JdbcPreparedStatement",
    "org.mariadb.jdbc.JdbcCallableStatement",
    "org.mariadb.jdbc.ServerSidePreparedStatement",
    "org.mariadb.jdbc.ClientSidePreparedStatement",
    "org.mariadb.jdbc.MariaDbServerPreparedStatement",
    "org.mariadb.jdbc.MariaDbClientPreparedStatement",
    "org.mariadb.jdbc.MySQLPreparedStatement",
    "org.mariadb.jdbc.MySQLCallableStatement",
    "org.mariadb.jdbc.MySQLServerSidePreparedStatement",
    // should completely cover postgresql
    "org.postgresql.jdbc1.PreparedStatement",
    "org.postgresql.jdbc1.CallableStatement",
    "org.postgresql.jdbc1.Jdbc1PreparedStatement",
    "org.postgresql.jdbc1.Jdbc1CallableStatement",
    "org.postgresql.jdbc2.PreparedStatement",
    "org.postgresql.jdbc2.CallableStatement",
    "org.postgresql.jdbc2.Jdbc2PreparedStatement",
    "org.postgresql.jdbc2.Jdbc2CallableStatement",
    "org.postgresql.jdbc3.Jdbc3PreparedStatement",
    "org.postgresql.jdbc3.Jdbc3CallableStatement",
    "org.postgresql.jdbc3g.Jdbc3gPreparedStatement",
    "org.postgresql.jdbc3g.Jdbc3gCallableStatement",
    "org.postgresql.jdbc4.Jdbc4PreparedStatement",
    "org.postgresql.jdbc4.Jdbc4CallableStatement",
    "org.postgresql.jdbc.PgPreparedStatement",
    "org.postgresql.jdbc.PgCallableStatement",
    "postgresql.PreparedStatement",
    "postgresql.CallableStatement",
    // should completely cover sqlite
    "org.sqlite.jdbc3.JDBC3PreparedStatement",
    "org.sqlite.jdbc4.JDBC4PreparedStatement",
    "org.sqlite.PrepStmt",
    // for testing purposes
    "test.TestPreparedStatement",
    // this won't match any classes unless set
    Config.get().getJdbcPreparedStatementClassName()
  };

  public PreparedStatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(CONCRETE_TYPES);
  }
}
