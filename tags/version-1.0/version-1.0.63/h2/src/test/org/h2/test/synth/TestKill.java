/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.synth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;

import org.h2.test.TestBase;

public class TestKill extends TestBase {

    Connection conn;
    int accounts = 10;
    Random random = new Random(1);
    private static final String DIR = "synth";

    public void test() throws Exception {
        String connect = "";

        connect = ";MAX_LOG_SIZE=10;THROTTLE=80";

        String url = getURL(DIR + "/kill" + connect, true);
        String user = getUser();
        String password = getPassword();

        String[] procDef = new String[] { "java.exe", "-cp", "bin", "org.h2.test.synth.TestKillProcess", url, user,
                password, baseDir, "" + accounts };

        for (int i = 0;; i++) {
            printTime("TestKill " + i);
            if (i % 10 == 0) {
                trace("deleting db...");
                deleteDb(baseDir, "kill");
            }
            conn = getConnection(url);
            createTables();
            checkData();
            initData();
            conn.close();
            Process proc = Runtime.getRuntime().exec(procDef);
            // while(true) {
            // int ch = proc.getErrorStream().read();
            // if(ch < 0) {
            // break;
            // }
            // System.out.print((char)ch);
            // }
            int runtime = random.nextInt(10000);
            trace("running...");
            Thread.sleep(runtime);
            trace("stopping...");
            proc.destroy();
            proc.waitFor();
            trace("stopped");
        }
    }

    private void createTables() throws SQLException {
        trace("createTables...");
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE IF NOT EXISTS ACCOUNT(ID INT PRIMARY KEY, SUM INT)");
        stat
                .execute("CREATE TABLE IF NOT EXISTS LOG(ID IDENTITY, ACCOUNTID INT, AMOUNT INT, FOREIGN KEY(ACCOUNTID) REFERENCES ACCOUNT(ID))");
        stat.execute("CREATE TABLE IF NOT EXISTS TEST_A(ID INT PRIMARY KEY, DATA VARCHAR)");
        stat.execute("CREATE TABLE IF NOT EXISTS TEST_B(ID INT PRIMARY KEY, DATA VARCHAR)");
    }

    private void initData() throws SQLException {
        trace("initData...");
        conn.createStatement().execute("DROP TABLE LOG");
        conn.createStatement().execute("DROP TABLE ACCOUNT");
        conn.createStatement().execute("DROP TABLE TEST_A");
        conn.createStatement().execute("DROP TABLE TEST_B");
        createTables();
        PreparedStatement prep = conn.prepareStatement("INSERT INTO ACCOUNT VALUES(?, 0)");
        for (int i = 0; i < accounts; i++) {
            prep.setInt(1, i);
            prep.execute();
        }
        PreparedStatement p1 = conn.prepareStatement("INSERT INTO TEST_A VALUES(?, '')");
        PreparedStatement p2 = conn.prepareStatement("INSERT INTO TEST_B VALUES(?, '')");
        for (int i = 0; i < accounts; i++) {
            p1.setInt(1, i);
            p2.setInt(1, i);
            p1.execute();
            p2.execute();
        }
    }

    private void checkData() throws Exception {
        trace("checkData...");
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM ACCOUNT ORDER BY ID");
        PreparedStatement prep = conn.prepareStatement("SELECT SUM(AMOUNT) FROM LOG WHERE ACCOUNTID=?");
        while (rs.next()) {
            int account = rs.getInt(1);
            int sum = rs.getInt(2);
            prep.setInt(1, account);
            ResultSet rs2 = prep.executeQuery();
            rs2.next();
            int sumLog = rs2.getInt(1);
            check(sumLog, sum);
            trace("account=" + account + " sum=" + sum);
        }
        PreparedStatement p1 = conn.prepareStatement("SELECT * FROM TEST_A WHERE ID=?");
        PreparedStatement p2 = conn.prepareStatement("SELECT * FROM TEST_B WHERE ID=?");
        for (int i = 0; i < accounts; i++) {
            p1.setInt(1, i);
            p2.setInt(1, i);
            ResultSet r1 = p1.executeQuery();
            ResultSet r2 = p2.executeQuery();
            boolean hasData = r1.next();
            check(r2.next(), hasData);
            if (hasData) {
                String d1 = r1.getString("DATA");
                String d2 = r2.getString("DATA");
                check(d1, d2);
                checkFalse(r1.next());
                checkFalse(r2.next());
                trace("test: data=" + d1);
            } else {
                trace("test: empty");
            }
        }
    }

}