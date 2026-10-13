package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmInventoryService;
import com.ruoyi.hospital.service.ITcmPatientFileService;

/** Exercise real database rollback through the same Spring transaction advice as production. */
class TcmConsultationTransactionTest {
    private JdbcTemplate jdbc;
    private TcmConsultationMapper mapper;
    private ITcmConsultationService service;
    private TcmConsultation incoming;

    @BeforeEach void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE stock (quantity INT)"); jdbc.update("INSERT INTO stock VALUES (100)");
        jdbc.execute("CREATE TABLE consultation (id VARCHAR(64), payload CLOB)");
        TcmConsultationServiceImpl target = new TcmConsultationServiceImpl();
        mapper = mock(TcmConsultationMapper.class);
        ITcmInventoryService inventory = mock(ITcmInventoryService.class);
        ReflectionTestUtils.setField(target, "consultationMapper", mapper);
        ReflectionTestUtils.setField(target, "inventoryService", inventory);
        ReflectionTestUtils.setField(target, "patientMapper", mock(TcmPatientMapper.class));
        ReflectionTestUtils.setField(target, "patientFileService", mock(ITcmPatientFileService.class));
        when(inventory.deductFromPrescription(anyList(), eq("raw_herbs"))).thenAnswer(call -> {
            jdbc.update("UPDATE stock SET quantity = quantity - 14");
            return Map.of("success", true, "deducted", List.of(Map.of("inventoryId", "stock", "name", "QA herb", "quantity", 14)));
        });
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        service = (ITcmConsultationService) factory.getProxy();
        incoming = new TcmConsultation(); incoming.setId("qa"); incoming.setConsultationId("QA-1");
        incoming.setPatientId("patient"); incoming.setStatus("draft"); incoming.setConsultDate("2026-10-13 09:00:00");
        incoming.setPayload(JSONObject.of("prescriptions", List.of(Map.of("id", "rx", "quantity", 7,
                "prescriptionType", "raw_herbs", "items", List.of(Map.of("name", "QA herb", "inventoryId", "stock", "dosage", 2, "convertedQty", 14, "unit", "g"))))).toJSONString());
    }

    @Test void databaseFailureAfterDeductionRollsBackBothStockAndNewConsultation() {
        when(mapper.insertTcmConsultation(any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO consultation VALUES (?, ?)", incoming.getId(), incoming.getPayload());
            throw new IllegalStateException("Simulated database failure after stock deduction");
        });
        assertThrows(IllegalStateException.class, () -> service.insertTcmConsultation(incoming));
        assertEquals(100, jdbc.queryForObject("SELECT quantity FROM stock", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM consultation", Integer.class));
    }

    @Test void successfulCopyCommitsBothStockAndSavedConsultation() {
        when(mapper.insertTcmConsultation(any())).thenAnswer(call -> jdbc.update("INSERT INTO consultation VALUES (?, ?)", incoming.getId(), incoming.getPayload()));
        assertEquals(1, service.insertTcmConsultation(incoming));
        assertEquals(86, jdbc.queryForObject("SELECT quantity FROM stock", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consultation", Integer.class));
        JSONObject saved = JSONObject.parseObject(jdbc.queryForObject("SELECT payload FROM consultation", String.class));
        assertEquals(14, saved.getJSONArray("prescriptions").getJSONObject(0).getJSONArray("inventoryReservation").getJSONObject(0).getIntValue("reservedQty"));
    }
}
