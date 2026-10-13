package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.*;
import com.ruoyi.hospital.mapper.*;
import com.ruoyi.hospital.service.*;

/** Verify stock and the idempotency marker roll back together through Spring transaction advice. */
class TcmInvoiceImportTransactionTest
{
    private static final String ID = "ad5c8e20-0938-4a6b-9a80-b1e053c4d320";
    private JdbcTemplate jdbc;
    private TcmInvoiceImportService service;
    private TcmClinicSettingMapper settings;
    private ITcmInventoryService inventory;

    @BeforeEach void setup()
    {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE stock (id VARCHAR(32) PRIMARY KEY, quantity DECIMAL(18,2))");
        jdbc.update("INSERT INTO stock VALUES ('i1', 8), ('i2', 10)");
        jdbc.execute("CREATE TABLE invoice (payload CLOB)");
        jdbc.update("INSERT INTO invoice VALUES (?)", JSONObject.of("createdAt", System.currentTimeMillis()).toJSONString());
        TcmInvoiceImportService target = new TcmInvoiceImportService();
        settings = mock(TcmClinicSettingMapper.class);
        TcmInventoryItemMapper mapper = mock(TcmInventoryItemMapper.class);
        ITcmHerbDictService herbs = mock(ITcmHerbDictService.class);
        inventory = mock(ITcmInventoryService.class);
        ReflectionTestUtils.setField(target, "settingMapper", settings);
        ReflectionTestUtils.setField(target, "inventoryMapper", mapper);
        ReflectionTestUtils.setField(target, "inventoryService", inventory);
        ReflectionTestUtils.setField(target, "herbService", herbs);
        when(settings.selectSettingForUpdate("inventoryInvoice:" + ID)).thenAnswer(call -> {
            TcmClinicSetting setting = new TcmClinicSetting(); setting.setSettingKey("inventoryInvoice:" + ID);
            setting.setSettingValue(jdbc.queryForObject("SELECT payload FROM invoice", String.class)); return setting;
        });
        when(settings.updateSetting(any())).thenAnswer(call -> jdbc.update("UPDATE invoice SET payload = ?", ((TcmClinicSetting) call.getArgument(0)).getSettingValue()));
        TcmHerbDict herb = new TcmHerbDict(); herb.setId("h1"); herb.setName("QA herb"); herb.setIsActive(1);
        when(herbs.selectTcmHerbDictById("h1")).thenReturn(herb);
        when(mapper.selectTcmInventoryItemForUpdate(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            TcmInventoryItem item = new TcmInventoryItem(); item.setId(id); item.setHerbDictId("h1"); item.setCategory("powder");
            item.setIsActive(1); item.setUnit("bag");
            item.setQuantity(jdbc.queryForObject("SELECT quantity FROM stock WHERE id = ? FOR UPDATE", BigDecimal.class, id));
            return item;
        });
        when(inventory.updateTcmInventoryItem(any())).thenAnswer(call -> {
            TcmInventoryItem item = call.getArgument(0);
            return jdbc.update("UPDATE stock SET quantity = ? WHERE id = ?", item.getQuantity(), item.getId());
        });
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (TcmInvoiceImportService) factory.getProxy();
    }

    private Map<String, Object> row(String id)
    {
        return Map.of("inventoryId", id, "herbDictId", "h1", "category", "powder", "unit", "bag", "quantity", 3, "purchasePrice", 2);
    }

    private Map<String, Object> request(List<Map<String, Object>> rows)
    {
        return Map.of("invoiceId", ID, "currency", "CAD", "items", rows);
    }

    private void assertStock(String id, String expected)
    {
        assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject("SELECT quantity FROM stock WHERE id = ?", BigDecimal.class, id)));
    }

    @Test void zeroRowsForLaterStockUpdateRollsBackEarlierLinesAndAllowsOneSuccessfulRetry()
    {
        doReturn(0).when(inventory).updateTcmInventoryItem(argThat(item -> "i2".equals(item.getId())));
        Map<String, Object> body = request(List.of(row("i1"), row("i2")));
        assertThrows(ServiceException.class, () -> service.confirm(body));
        assertStock("i1", "8"); assertStock("i2", "10");
        assertFalse(JSONObject.parseObject(jdbc.queryForObject("SELECT payload FROM invoice", String.class)).getBooleanValue("imported"));
        doAnswer(call -> {
            TcmInventoryItem item = call.getArgument(0);
            return jdbc.update("UPDATE stock SET quantity = ? WHERE id = ?", item.getQuantity(), item.getId());
        }).when(inventory).updateTcmInventoryItem(argThat(item -> "i2".equals(item.getId())));
        service.confirm(body);
        assertEquals(true, service.confirm(body).get("alreadyImported"));
        assertStock("i1", "11"); assertStock("i2", "13");
    }

    @Test void failedInvoiceMarkerRollsBackStockInsteadOfAllowingDuplicateImport()
    {
        doReturn(0).when(settings).updateSetting(any());
        assertThrows(ServiceException.class, () -> service.confirm(request(List.of(row("i1")))));
        assertStock("i1", "8");
        assertFalse(JSONObject.parseObject(jdbc.queryForObject("SELECT payload FROM invoice", String.class)).getBooleanValue("imported"));
    }

    @Test void zeroRowsForNewStockCannotMarkInvoiceImported()
    {
        Map<String, Object> newRow = new java.util.LinkedHashMap<>(row("i1")); newRow.remove("inventoryId");
        assertThrows(ServiceException.class, () -> service.confirm(request(List.of(newRow))));
        assertFalse(JSONObject.parseObject(jdbc.queryForObject("SELECT payload FROM invoice", String.class)).getBooleanValue("imported"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock", Integer.class));
    }
}
