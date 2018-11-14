/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.qa.jdbc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.SuppressForbidden;
import org.junit.rules.ExternalResource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Locale;
import java.util.Properties;

public class LocalH2 extends ExternalResource implements CheckedSupplier<Connection, SQLException> {
    private final Logger logger = LogManager.getLogger(getClass());

    /*
     * The syntax on the connection string is fairly particular:
     *      mem:; creates an anonymous database in memory. The `;` is
     *              technically the separator that comes after the name.
     *      DATABASE_TO_UPPER=false turns *off* H2's Oracle-like habit
     *              of upper-casing everything that isn't quoted.
     *      ALIAS_COLUMN_NAME=true turn *on* returning alias names in
     *              result set metadata which is what most DBs do except
     *              for MySQL and, by default, H2. Our jdbc driver does it.
     */
    // http://www.h2database.com/html/features.html#in_memory_databases
    private static String memUrl(String name) {
        String n = name == null ? "" : name;
        return "jdbc:h2:mem:" + n + ";DATABASE_TO_UPPER=false;ALIAS_COLUMN_NAME=true";
    }

    static {
        try {
            // Initialize h2 so we can use it for testing
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates an in memory anonymous database and returns the only connection to it.
     * Closing the connection will remove the db.
     */
    public static Connection anonymousDb() throws SQLException {
        return DriverManager.getConnection(memUrl(null));
    }

    private static final Properties DEFAULTS = new Properties();

    private final String url;
    // H2 in-memory will keep the db alive as long as this connection is opened
    private Connection keepAlive;
    Locale locale;

    private CheckedConsumer<Connection, SQLException> initializer;

    public LocalH2(CheckedConsumer<Connection, SQLException> initializer) {
        this.url = memUrl("essql");
        this.initializer = initializer;
    }

    @Override
    @SuppressForbidden(reason = "H2 gets really confused with non Gregorian calendars")
    protected void before() throws Throwable {
        if ("japanese".equals(Calendar.getInstance().getCalendarType())) {
            logger.info("Japanese calendar is detected. Overriding locale.");
            locale = Locale.getDefault();
            Locale.setDefault(locale.stripExtensions()); // removes the calendar setting
            assert "gregory".equals(Calendar.getInstance().getCalendarType());
        }
        keepAlive = get();
        initializer.accept(keepAlive);
    }

    @Override
    protected void after() {
        try {
            keepAlive.close();
        } catch (SQLException ex) {
            // close
        }
        if (locale != null) {
            logger.info("Restoring locale.");
            Locale.setDefault(locale);
            locale = null;
        }
    }

    @Override
    public Connection get() throws SQLException {
        return DriverManager.getConnection(url, DEFAULTS);
    }
}