// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.cql;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Test;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class TestInsert extends BaseCQLTest {

  @Test
  public void testSimpleInsert() throws Exception {
    LOG.info("TEST SIMPLE INSERT - Start");

    // Setup table and insert 100 rows.
    setupTable("test_insert", 100);

    LOG.info("TEST SIMPLE INSERT - End");
  }

  @Test
  public void testLargeInsert() throws Exception {
    final int STRING_SIZE = 4 * 1024 * 1024;
    String tableName = "test_large_insert";
    String create_stmt = String.format(
        "CREATE TABLE %s (h int PRIMARY KEY, c varchar);", tableName);
    session.execute(create_stmt);
    String ins_stmt = "INSERT INTO test_large_insert (h, c) VALUES (1, ?);";
    String value = RandomStringUtils.randomAscii(STRING_SIZE);
    session.execute(ins_stmt, value);
    String sel_stmt = String.format("SELECT h, c FROM %s"
        + " WHERE h = 1 ;", tableName);
    Row row = runSelect(sel_stmt).next();
    assertEquals(1, row.getInt(0));
    assertEquals(value, row.getString(1));
  }

  @Test
    public void testInsertWithTimestamp() throws Exception {
      String tableName = "test_insert_with_timestamp";
      createTable(tableName, "timestamp");
      // this includes both string and int inputs
      Map<String, Date> ts_values = generateTimestampMap();
      for (String key : ts_values.keySet()) {
        Date date_value = ts_values.get(key);
        String ins_stmt = String.format(
                "INSERT INTO %s(h1, h2, r1, r2, v1, v2) VALUES(%d, %s, %d, %s, %d, %s);",
                tableName, 1, key, 2, key, 3, key);
        session.execute(ins_stmt);
        String sel_stmt = String.format("SELECT h1, h2, r1, r2, v1, v2 FROM %s"
                + " WHERE h1 = 1 AND h2 = %s;", tableName, key);
        Row row = runSelect(sel_stmt).next();
        assertEquals(1, row.getInt(0));
        assertEquals(2, row.getInt(2));
        assertEquals(3, row.getInt(4));
        assertEquals(date_value, row.getTimestamp(1));
        assertEquals(date_value, row.getTimestamp(3));
        assertEquals(date_value, row.getTimestamp(5));
      }
    }

  private void runInvalidInsertWithTimestamp(String tableName, String ts) {
    String insert_stmt = String.format(
            "INSERT INTO %s(h1, h2, r1, r2, v1, v2) VALUES(%d, %d, %d, %d, %d, '%s');",
            tableName, 1, 2, 3, 4, 5, ts);
    runInvalidStmt(insert_stmt);
  }

  @Test
  public void testInvalidInsertWithTimestamp() throws Exception {
    String tableName = "test_insert_with_invalid_timestamp";
    createTable(tableName, "timestamp");

    runInvalidInsertWithTimestamp(tableName, "plainstring");
    runInvalidInsertWithTimestamp(tableName, "1992:12:11");
    runInvalidInsertWithTimestamp(tableName, "1992-11");
    runInvalidInsertWithTimestamp(tableName, "1992-13-12");
    runInvalidInsertWithTimestamp(tableName, "1992-12-12 14:23:30:31");
    runInvalidInsertWithTimestamp(tableName, "1992-12-12 14:23:30.12.32");
  }

  @Test
  public void testInsertWithTTL() throws Exception {
    String tableName = "test_insert_with_ttl";
    createTable(tableName);

    // Now insert with ttl.
    String insert_stmt = String.format(
      "INSERT INTO %s(h1, h2, r1, r2, v1) VALUES(%d, 'h%d', %d, 'r%d', %d) USING TTL 2;",
      tableName, 1, 2, 3, 4, 5);
    session.execute(insert_stmt);

    insert_stmt = String.format(
      "INSERT INTO %s(h1, h2, r1, r2, v2) VALUES(%d, 'h%d', %d, 'r%d', 'v%d') USING TTL 4;",
      tableName, 1, 2, 3, 4, 6);
    session.execute(insert_stmt);

    String select_stmt = String.format("SELECT h1, h2, r1, r2, v1, v2 FROM %s"
      + "  WHERE h1 = 1 AND h2 = 'h2';", tableName);

    // Verify row is present.
    Row row = runSelect(select_stmt).next();
    assertEquals(1, row.getInt(0));
    assertEquals("h2", row.getString(1));
    assertEquals(3, row.getInt(2));
    assertEquals("r4", row.getString(3));
    assertEquals(5, row.getInt(4));
    assertEquals("v6", row.getString(5));

    // Now verify v1 expires.
    Thread.sleep(2100);
    row = runSelect(select_stmt).next();
    assertEquals(1, row.getInt(0));
    assertEquals("h2", row.getString(1));
    assertEquals(3, row.getInt(2));
    assertEquals("r4", row.getString(3));
    assertTrue(row.isNull(4));
    assertEquals("v6", row.getString(5));

    // Now verify v2 expires.
    Thread.sleep(2000);
    assertNoRow(select_stmt);
  }

  private String getInsertStmt(String tableName, String ttlSeconds) {
    return String.format(
      "INSERT INTO %s(h1, h2, r1, r2, v1) VALUES(%d, 'h%d', %d, 'r%d', %d) USING TTL %s;",
      tableName, 1, 2, 3, 4, 5, ttlSeconds);
  }

  private void runInvalidInsertWithTTL(String tableName, String ttlSeconds) {
    runInvalidStmt(getInsertStmt(tableName, ttlSeconds));
  }

  private void runValidInsertWithTTL(String tableName, String ttlSeconds) {
    session.execute(getInsertStmt(tableName, ttlSeconds));
  }

  @Test
  public void testInvalidInsertWithTTL() throws Exception {
    String tableName = "test_insert_with_invalid_ttl";
    createTable(tableName);

    runValidInsertWithTTL(tableName, String.valueOf(MAX_TTL_SEC));
    runValidInsertWithTTL(tableName, "0");

    runInvalidInsertWithTTL(tableName, String.valueOf(MAX_TTL_SEC + 1));
    runInvalidInsertWithTTL(tableName, String.valueOf(Long.MAX_VALUE));
    runInvalidInsertWithTTL(tableName, "1000.1");
    runInvalidInsertWithTTL(tableName, "abcxyz");
    runInvalidInsertWithTTL(tableName, "-1");
    runInvalidInsertWithTTL(tableName, "0x80");
    runInvalidInsertWithTTL(tableName, "true");
    runInvalidInsertWithTTL(tableName, "false");
  }

  @Test
  public void testResetTTL() throws Exception {
    // Setting TTL to 0 should reset the TTL.
    String tableName = "test_reset_ttl";
    createTable(tableName);

    // Insert two rows with TTL 1000
    String insert_stmt = String.format(
      "INSERT INTO %s(h1, h2, r1, r2, v1, v2) VALUES(%d, 'h%d', %d, 'r%d', %d, 'v%d') USING TTL " +
        "2;",
      tableName, 1, 2, 3, 4, 5, 6);
    session.execute(insert_stmt);

    insert_stmt = String.format(
      "INSERT INTO %s(h1, h2, r1, r2, v1, v2) VALUES(%d, 'h%d', %d, 'r%d', %d, 'v%d') USING TTL " +
        "2;",
      tableName, 10, 20, 30, 40, 50, 60);
    session.execute(insert_stmt);

    // Set TTL to 0 for one row.
    insert_stmt = String.format(
      "INSERT INTO %s(h1, h2, r1, r2, v1, v2) VALUES(%d, 'h%d', %d, 'r%d', %d, 'v%d') USING TTL " +
        "0;",
      tableName, 10, 20, 30, 40, 50, 60);
    session.execute(insert_stmt);

    // Verify one row exists and the other doesn't.
    String select_stmt = String.format("SELECT h1, h2, r1, r2, v1, v2 FROM %s"
      + "  WHERE h1 = 1 AND h2 = 'h2';", tableName);

    Thread.sleep(2100);

    // Verify row has expired.
    assertNoRow(select_stmt);

    // Verify row with TTL reset survives.
    select_stmt = String.format("SELECT h1, h2, r1, r2, v1, v2 FROM %s"
      + "  WHERE h1 = 10 AND h2 = 'h20';", tableName);
    Row row = runSelect(select_stmt).next();
    assertEquals(10, row.getInt(0));
    assertEquals("h20", row.getString(1));
    assertEquals(30, row.getInt(2));
    assertEquals("r40", row.getString(3));
    assertEquals(50, row.getInt(4));
    assertEquals("v60", row.getString(5));
  }

  @Test
  public void testInsertWithInet() throws Exception {
    String tableName = "table_with_inet";
    session.execute(String.format("CREATE TABLE %s (c1 inet, c2 inet, c3 int, c4 " +
      "inet, c5 inet, PRIMARY KEY(c1, c2, c3));", tableName));
    session.execute(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values ('1.2.3.4', " +
      "'fe80::2978:9018:b288:3f6c', 1, 'fe80::9929:23c3:8309:c29f', '10.10.10.10');", tableName));
    ResultSet rs = session.execute(String.format("SELECT c1, c2, c3, c4, c5 FROM %s WHERE c1 = " +
      "'1.2.3.4' AND c2 = 'fe80::2978:9018:b288:3f6c' AND c3 = 1;", tableName));
    Row row = rs.one();

    assertEquals(InetAddress.getByName("1.2.3.4"), row.getInet("c1"));
    assertEquals(InetAddress.getByName("fe80::2978:9018:b288:3f6c"), row.getInet("c2"));
    assertEquals(1, row.getInt("c3"));
    assertEquals(InetAddress.getByName("fe80::9929:23c3:8309:c29f"), row.getInet("c4"));
    assertEquals(InetAddress.getByName("10.10.10.10"), row.getInet("c5"));
    assertNull(rs.one());

    // Try blobs and int.
    session.execute(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values ('0xff', " +
        "'4294967295', 1, '0xffffffff', '291913250');", tableName));
    row = session.execute(String.format("SELECT c1, c2, c3, c4, c5 FROM %s WHERE c1 = '0xff';",
      tableName)).one();
    assertEquals(InetAddress.getByName("0.0.0.255"), row.getInet("c1"));
    assertEquals(InetAddress.getByName("255.255.255.255"), row.getInet("c2"));
    assertEquals(1, row.getInt("c3"));
    assertEquals(InetAddress.getByName("255.255.255.255"), row.getInet("c4"));
    assertEquals(InetAddress.getByName("17.102.62.34"), row.getInet("c5"));

    // Now try a bunch of invalid inserts.
    // 1.2.3.400 invalid IPv4
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values ('1.2.3.400', " +
      "'fe80::2978:9018:b288:3f6c', 1, 'fe80::9929:23c3:8309:c29f', '10.10.10.10');", tableName));
    // fe80::2978:9018:b288:3z6c invalid IPv6
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values ('1.2.3.4', " +
      "'fe80::2978:9018:b288:3z6c', 1, 'fe80::9929:23c3:8309:c29f', '10.10.10.10');", tableName));
    // Invalid types.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (1, " +
      "'fe80::2978:9018:b288:3f6c', 1, 'fe80::9929:23c3:8309:c29f', '10.10.10.10');", tableName));
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (3.1, " +
      "'fe80::2978:9018:b288:3f6c', 1, 'fe80::9929:23c3:8309:c29f', '10.10.10.10');", tableName));
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (true, " +
      "'fe80::2978:9018:b288:3f6c', 1, 'fe80::9929:23c3:8309:c29f', '10.10.10.10');", tableName));
  }

  @Test
  public void testInsertWithUuid() throws Exception {
    String tableName = "table_with_uuid";
    session.execute(String.format("CREATE TABLE %s (c1 uuid, c2 uuid, c3 int, c4 " +
      "uuid, c5 uuid, PRIMARY KEY(c3));", tableName));
    session.execute(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-4173-bced-0eba570d969e, " +
      "c57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));
    // TODO: Test for UUID in where clause after literals.
    ResultSet rs = session.execute(
      "SELECT c1, c2, c3, c4, c5 FROM table_with_uuid WHERE c3 = ?",
      1);
    Row row = rs.one();

    assertEquals(UUID.fromString("467c4b82-ef22-4173-bced-0eba570d969e"), row.getUUID("c1"));
    assertEquals(UUID.fromString("c57c4b82-ef52-2073-aced-0eba570d969e"), row.getUUID("c2"));
    assertEquals(1, row.getInt("c3"));
    assertEquals(UUID.fromString("157c4b82-ff32-1073-bced-0eba570d969e"), row.getUUID("c4"));
    assertEquals(UUID.fromString("b57c4b82-ef52-1173-bced-0eba570d969e"), row.getUUID("c5"));
    assertNull(rs.one());

    // Now try a bunch of invalid inserts.
    // Short UUIDs for c1.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "67c4b82-ef22-4173-bced-0eba570d969e, " +
      "c57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-e22-4173-bced-0eba570d969e, " +
      "c57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-413-bced-0eba570d969e, " +
      "c57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-4173-bced-0eba570d96, " +
      "c57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    // Invalid type for c1.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "2, " +
      "c57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    // Invalid UUID for c2.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-4173-bced-0eba570d969e, " +
      "X57c4b82-ef52-2073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));
  }


  @Test
  public void testInsertWithTimeUuid() throws Exception {
    String tableName = "table_with_timeuuid";
    session.execute(String.format("CREATE TABLE %s (c1 timeuuid, c2 timeuuid, c3 int, " +
      "c4 timeuuid, c5 timeuuid, PRIMARY KEY(c3));", tableName));
    session.execute(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-1173-bced-0eba570d969e, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));
    // TODO: Test for UUID in where clause after literals.
    ResultSet rs = session.execute(
      "SELECT c1, c2, c3, c4, c5 FROM table_with_timeuuid WHERE c3 = ?",
      1);
    Row row = rs.one();

    assertEquals(UUID.fromString("467c4b82-ef22-1173-bced-0eba570d969e"), row.getUUID("c1"));
    assertEquals(UUID.fromString("c57c4b82-ef52-1073-aced-0eba570d969e"), row.getUUID("c2"));
    assertEquals(1, row.getInt("c3"));
    assertEquals(UUID.fromString("157c4b82-ff32-1073-bced-0eba570d969e"), row.getUUID("c4"));
    assertEquals(UUID.fromString("b57c4b82-ef52-1173-bced-0eba570d969e"), row.getUUID("c5"));
    assertNull(rs.one());

    // Now try a bunch of invalid inserts.
    // Short TimeUUIDs for c1.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "67c4b82-ef22-1173-bced-0eba570d969e, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-e22-1173-bced-0eba570d969e, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-113-bced-0eba570d969e, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-1173-bced-0eba570d96, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    // Invalid type for c1.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "2, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    // Invalid UUID for c2.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-1173-bced-0eba570d969e, " +
      "X57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-1073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));

    // Valid UUID for c1, invalid TIMEUUID for c4.
    runInvalidStmt(String.format("INSERT INTO %s (c1, c2, c3, c4, c5) values (" +
      "467c4b82-ef22-1173-bced-0eba570d969e, " +
      "c57c4b82-ef52-1073-aced-0eba570d969e, 1, " +
      "157c4b82-ff32-4073-bced-0eba570d969e, " +
      "b57c4b82-ef52-1173-bced-0eba570d969e);", tableName));
  }

  @Test
  public void testInsertIntoSystemNamespace() throws Exception {
    runInvalidStmt("INSERT INTO system.peers (h1, h2, r1, r2) VALUES (1, '1', 1, '1');");
    runInvalidStmt("DELETE FROM system.peers WHERE h1 = 1 AND h2 = '1' AND r1 = 1 AND r2 = '1';");
  }

  @Test
  public void testInsertIntoRecreatedTable() throws Exception {
    // Create table with a TIMESTAMP column and insert a value.
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ");
    df.setTimeZone(TimeZone.getTimeZone("+0000"));
    Date timestamp = df.parse("2017-01-01 00:00:00+0000");
    session.execute("CREATE TABLE t (h int PRIMARY KEY, c timestamp);");
    session.execute(String.format("INSERT INTO t (h, c) VALUES (1, '%s');", df.format(timestamp)));

    // Verify the timestamp is inserted.
    Row row = runSelect("SELECT * FROM t;").next();
    assertEquals(1, row.getInt(0));
    assertEquals(df.format(timestamp), df.format(row.getTimestamp(1)));

    // Drop and create a new table of the same name but different column datatype. Insert a value.
    session.execute("DROP TABLE t;");
    session.execute("CREATE TABLE t (h int PRIMARY KEY, c varchar);");
    session.execute("INSERT INTO t (h, c) VALUES (1, 'hello');");

    // Verify the value is inserted.
    assertQuery("SELECT * FROM t;", "Row[1, hello]");
  }
}
