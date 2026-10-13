package com.ruoyi.hospital.mapper;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.hospital.domain.TcmAppointment;

class TcmAppointmentMapperIntegrationTest {
    private SqlSession session;
    private TcmAppointmentMapper mapper;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tcm_appointment (id VARCHAR(64) PRIMARY KEY, patient_id VARCHAR(64), practitioner_id VARCHAR(64),"
                + "room_id VARCHAR(64), service_type VARCHAR(64), start_time VARCHAR(32), end_time VARCHAR(32), status VARCHAR(32),"
                + "branch_id VARCHAR(64), intake_token VARCHAR(64), intake_submitted INT, payload VARCHAR_IGNORECASE(10000),"
                + "create_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.update("INSERT INTO tcm_appointment (id,start_time,end_time,status,intake_token,intake_submitted,payload) VALUES (?,?,?,?,?,?,?)",
                "appointment", "2026-10-13 09:00:00", "2026-10-13 10:00:00", "booked", "current-token", 0, "{\"notes\":\"A\"}");
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        configuration.getTypeAliasRegistry().registerAlias("TcmAppointment", TcmAppointment.class);
        String resource = "mapper/hospital/TcmAppointmentMapper.xml";
        String xml;
        try (java.io.InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream); xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // H2 spells unbounded binary casts VARBINARY and its current timestamp differs from MySQL.
        xml = xml.replace("sysdate()", "CURRENT_TIMESTAMP").replace(" AS BINARY)", " AS VARBINARY)");
        new XMLMapperBuilder(new StringReader(xml), configuration, resource, configuration.getSqlFragments()).parse();
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(TcmAppointmentMapper.class);
    }

    @AfterEach void cleanup() { if (session != null) session.close(); }

    @Test void sparseAppointmentEditPreservesIntakeAndExplicitConsumptionCanClearIt() {
        TcmAppointment edit = new TcmAppointment(); edit.setId("appointment"); edit.setStatus("confirmed");
        assertEquals(1, mapper.updateTcmAppointment(edit));
        TcmAppointment current = mapper.selectTcmAppointmentForUpdate("appointment");
        assertEquals("current-token", current.getIntakeToken()); assertEquals(0, current.getIntakeSubmitted());
        TcmAppointment submitted = new TcmAppointment(); submitted.setIntakeSubmitted(1);
        assertEquals(1, mapper.updateNotificationIntake(submitted, current));
        TcmAppointment result = mapper.selectTcmAppointmentById("appointment");
        assertNull(result.getIntakeToken()); assertEquals(1, result.getIntakeSubmitted());
    }

    @Test void notificationCasCannotOverwriteAnAppointmentMovedAfterItsSnapshot() {
        TcmAppointment before = mapper.selectTcmAppointmentById("appointment");
        TcmAppointment moved = new TcmAppointment(); moved.setId("appointment"); moved.setStartTime("2026-10-14 09:00:00");
        assertEquals(1, mapper.updateTcmAppointment(moved));
        TcmAppointment notification = new TcmAppointment(); notification.setPayload("{\"aftercareEmailSentAt\":\"sent\"}");
        assertEquals(0, mapper.updateNotificationPayload(notification, before));
        TcmAppointment latest = mapper.selectTcmAppointmentById("appointment");
        assertEquals("2026-10-14 09:00:00", latest.getStartTime());
        assertEquals("{\"notes\":\"A\"}", latest.getPayload());
    }

    @Test void notificationCasComparesPayloadBytesEvenWithCaseInsensitiveStorage() {
        TcmAppointment before = mapper.selectTcmAppointmentById("appointment");
        TcmAppointment edit = new TcmAppointment(); edit.setId("appointment"); edit.setPayload("{\"notes\":\"a\"}");
        assertEquals(1, mapper.updateTcmAppointment(edit));
        TcmAppointment notification = new TcmAppointment(); notification.setPayload("{\"aftercareEmailSentAt\":\"sent\"}");
        assertEquals(0, mapper.updateNotificationPayload(notification, before));
        assertEquals("{\"notes\":\"a\"}", mapper.selectTcmAppointmentById("appointment").getPayload());
    }
}
