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
import com.ruoyi.hospital.domain.TcmConsultation;

class TcmConsultationPdfMapperIntegrationTest
{
    private SqlSession session;
    private TcmConsultationMapper mapper;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() throws Exception
    {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tcm_consultation (id VARCHAR(64) PRIMARY KEY, status VARCHAR(32),"
                + "deleted_at VARCHAR(32), payload VARCHAR_IGNORECASE(10000), update_time TIMESTAMP)");
        jdbc.update("INSERT INTO tcm_consultation (id,status,deleted_at,payload) VALUES (?,?,?,?)",
                "consultation", "completed", "2026-10-13", "{\"notes\":\"A\"}");
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAlias("TcmConsultation", TcmConsultation.class);
        String resource = "mapper/hospital/TcmConsultationMapper.xml";
        String xml;
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Adapt only dialect names while executing the production mapper statements.
        xml = xml.replace("sysdate()", "CURRENT_TIMESTAMP").replace(" as binary)", " as varbinary)");
        new XMLMapperBuilder(new StringReader(xml), config, resource, config.getSqlFragments()).parse();
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(TcmConsultationMapper.class);
    }

    @AfterEach void cleanup() { if (session != null) session.close(); }

    @Test void metadataUpdateChangesOnlyPayloadAndPreservesDeletionAndClinicalStatus()
    {
        TcmConsultation update = update("{\"invoicePdfPath\":\"invoice.pdf\"}");
        assertEquals(1, mapper.updatePdfPayloadIfUnchanged(update, "{\"notes\":\"A\"}"));
        assertEquals("completed", jdbc.queryForObject("SELECT status FROM tcm_consultation", String.class));
        assertEquals("2026-10-13", jdbc.queryForObject("SELECT deleted_at FROM tcm_consultation", String.class));
        assertEquals(update.getPayload(), jdbc.queryForObject("SELECT payload FROM tcm_consultation", String.class));
    }

    @Test void stalePayloadIncludingCaseOnlyDifferenceCannotOverwriteNewerState()
    {
        jdbc.update("UPDATE tcm_consultation SET payload=?", "{\"notes\":\"a\"}");
        assertEquals(0, mapper.updatePdfPayloadIfUnchanged(update("{}"), "{\"notes\":\"A\"}"));
        assertEquals("{\"notes\":\"a\"}", jdbc.queryForObject("SELECT payload FROM tcm_consultation", String.class));
    }

    @Test void nullPayloadCanBeInitializedButCannotMatchExistingPayload()
    {
        assertEquals(0, mapper.updatePdfPayloadIfUnchanged(update("{}"), null));
        jdbc.update("UPDATE tcm_consultation SET payload=null");
        assertEquals(1, mapper.updatePdfPayloadIfUnchanged(update("{}"), null));
    }

    private TcmConsultation update(String payload)
    {
        TcmConsultation result = new TcmConsultation(); result.setId("consultation"); result.setPayload(payload); return result;
    }
}
