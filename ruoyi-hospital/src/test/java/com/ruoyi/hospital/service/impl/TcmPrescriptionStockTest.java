package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.hospital.domain.TcmInventoryItem;
import com.ruoyi.hospital.mapper.TcmInventoryItemMapper;
import com.ruoyi.hospital.service.ITcmAuditLogService;

class TcmPrescriptionStockTest
{
    private final TcmInventoryItemMapper mapper = mock(TcmInventoryItemMapper.class);
    private final ITcmAuditLogService audit = mock(ITcmAuditLogService.class);
    private final TcmInventoryServiceImpl service = new TcmInventoryServiceImpl();
    private TcmInventoryItem stock;

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "inventoryMapper", mapper);
        ReflectionTestUtils.setField(service, "auditLogService", audit);
        stock = new TcmInventoryItem();
        stock.setId("stock"); stock.setName("通草"); stock.setCategory("raw_herbs");
        stock.setUnit("g"); stock.setIsActive(1); stock.setQuantity(new BigDecimal("100"));
        when(mapper.selectTcmInventoryItemById("stock")).thenReturn(stock);
        when(mapper.selectTcmInventoryItemForUpdate("stock")).thenReturn(stock);
    }

    private Map<String, Object> line(String quantity)
    {
        return new LinkedHashMap<>(Map.of("inventoryId", "stock", "name", "通草",
                "quantity", quantity, "prescriptionId", "rx-1"));
    }

    @Test
    void repeatedHerbDeductsCumulativelyAndRestoresExactlyWithAudit()
    {
        Map<String, Object> result = service.deductFromPrescription(List.of(line("20"), line("30")), "raw_herbs");
        assertEquals(true, result.get("success"));
        assertEquals(new BigDecimal("50"), stock.getQuantity());
        verify(mapper, times(1)).selectTcmInventoryItemForUpdate("stock");
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit, times(2)).log(eq("inventory"), eq("stock"), eq("通草"), eq("PRESCRIPTION_DEDUCT"), anyString(), details.capture());
        assertTrue(details.getAllValues().get(0).contains("100 -> 80 g (-20)"));
        assertTrue(details.getAllValues().get(1).contains("[rx-1]: 80 -> 50 g (-30)"));
        result = service.restoreFromPrescription(List.of(line("20"), line("30")), "raw_herbs");
        assertEquals(true, result.get("success"));
        assertEquals(new BigDecimal("100"), stock.getQuantity());
        verify(audit, times(2)).log(eq("inventory"), eq("stock"), eq("通草"), eq("PRESCRIPTION_RESTORE"), anyString(), anyString());
    }

    @Test
    void repeatedHerbCannotOverdrawCombinedBalance()
    {
        Map<String, Object> result = service.deductFromPrescription(List.of(line("60"), line("60")), "raw_herbs");
        assertEquals(false, result.get("success"));
        assertEquals(new BigDecimal("100"), stock.getQuantity());
        verify(mapper, never()).updateTcmInventoryItem(any());
        verifyNoInteractions(audit);
    }

    @Test
    void validatesLockedBalanceInsteadOfEarlierSnapshot()
    {
        TcmInventoryItem current = new TcmInventoryItem();
        current.setId("stock"); current.setIsActive(1); current.setQuantity(new BigDecimal("10"));
        when(mapper.selectTcmInventoryItemForUpdate("stock")).thenReturn(current);
        assertEquals(false, service.deductFromPrescription(List.of(line("20")), "raw_herbs").get("success"));
        verify(mapper, never()).updateTcmInventoryItem(any());
    }

    @Test
    void manualAdjustmentUsesTheSameLockedBalanceAsPrescriptionChanges()
    {
        TcmInventoryItem current = new TcmInventoryItem();
        current.setId("stock"); current.setQuantity(new BigDecimal("50"));
        when(mapper.selectTcmInventoryItemForUpdate("stock")).thenReturn(current);
        assertEquals(new BigDecimal("55"), service.adjustStock("stock", new BigDecimal("5")).getQuantity());
        verify(mapper).selectTcmInventoryItemForUpdate("stock");
        verify(mapper, never()).selectTcmInventoryItemById("stock");
    }

    @Test
    void negativeMovementCannotIncreaseStockOrConsumeOnRestore()
    {
        assertEquals(false, service.deductFromPrescription(List.of(line("-5")), "raw_herbs").get("success"));
        assertEquals(false, service.restoreFromPrescription(List.of(line("-5")), "raw_herbs").get("success"));
        verify(mapper, never()).updateTcmInventoryItem(any());
        verifyNoInteractions(audit);
    }

    @Test
    void missingRestoreItemDoesNotSilentlyDropReservationOrPartiallyRestore()
    {
        Map<String, Object> missing = new LinkedHashMap<>(line("5")); missing.put("inventoryId", "missing");
        when(mapper.selectTcmInventoryItemsByName("通草", "raw_herbs")).thenReturn(List.of(stock));
        assertEquals(false, service.restoreFromPrescription(List.of(line("20"), missing), "raw_herbs").get("success"));
        assertEquals(new BigDecimal("100"), stock.getQuantity());
        verify(mapper, never()).updateTcmInventoryItem(any());
        verifyNoInteractions(audit);
    }

    @Test
    void archivedReservationReturnsToOriginalStockWithoutReactivatingOrUsingANamesake()
    {
        TcmInventoryItem deleted = new TcmInventoryItem();
        deleted.setId("deleted-stock"); deleted.setName("通草"); deleted.setCategory("raw_herbs");
        deleted.setSupplierId("original-supplier"); deleted.setIsActive(0);
        deleted.setDeletedAt("2026-10-01 10:00:00"); deleted.setQuantity(new BigDecimal("40"));
        stock.setSupplierId("other-supplier");
        when(mapper.selectTcmInventoryItemById("deleted-stock")).thenReturn(deleted);
        when(mapper.selectTcmInventoryItemForUpdate("deleted-stock")).thenReturn(deleted);
        when(mapper.selectTcmInventoryItemsByName("通草", "raw_herbs")).thenReturn(List.of(stock));
        Map<String, Object> reservation = new LinkedHashMap<>(line("5"));
        reservation.put("inventoryId", "deleted-stock");
        reservation.put("supplierId", "original-supplier");

        assertEquals(true, service.restoreFromPrescription(List.of(reservation), "raw_herbs").get("success"));
        assertEquals(false, service.deductFromPrescription(List.of(reservation), "raw_herbs").get("success"));
        assertEquals(new BigDecimal("100"), stock.getQuantity());
        assertEquals(new BigDecimal("45"), deleted.getQuantity());
        assertEquals(0, deleted.getIsActive());
        assertEquals("2026-10-01 10:00:00", deleted.getDeletedAt());
        verify(mapper).updateTcmInventoryItem(deleted);
        verify(mapper, never()).selectTcmInventoryItemsByName(anyString(), anyString());
        verify(audit).log(eq("inventory"), eq("deleted-stock"), eq("通草"),
                eq("PRESCRIPTION_RESTORE"), anyString(), anyString());
    }

    @Test
    void reservationCanReturnToInactiveStockButCannotDeductFromIt()
    {
        stock.setIsActive(0);
        assertEquals(true, service.restoreFromPrescription(List.of(line("5")), "raw_herbs").get("success"));
        assertEquals(new BigDecimal("105"), stock.getQuantity());
        assertEquals(0, stock.getIsActive());
        assertEquals(false, service.deductFromPrescription(List.of(line("5")), "raw_herbs").get("success"));
        verify(mapper).updateTcmInventoryItem(stock);
    }

    @Test
    void reservationReturnUsesArchivedStateFromLockedRow()
    {
        TcmInventoryItem current = new TcmInventoryItem();
        current.setId("stock"); current.setName("通草"); current.setCategory("raw_herbs");
        current.setIsActive(0); current.setDeletedAt("2026-10-13 10:00:00");
        current.setQuantity(new BigDecimal("50"));
        when(mapper.selectTcmInventoryItemForUpdate("stock")).thenReturn(current);

        assertEquals(true, service.restoreFromPrescription(List.of(line("5")), "raw_herbs").get("success"));
        assertEquals(new BigDecimal("55"), current.getQuantity());
        assertEquals("2026-10-13 10:00:00", current.getDeletedAt());
        verify(mapper).updateTcmInventoryItem(current);
    }

    @Test
    void legacyMovementWithoutInventoryIdStillUsesNameAndSupplier()
    {
        stock.setSupplierId("legacy-supplier");
        when(mapper.selectTcmInventoryItemsByName("通草", "raw_herbs")).thenReturn(List.of(stock));
        Map<String, Object> legacy = new LinkedHashMap<>(line("5"));
        legacy.remove("inventoryId");
        legacy.put("supplierId", "legacy-supplier");

        assertEquals(true, service.restoreFromPrescription(List.of(legacy), "raw_herbs").get("success"));
        assertEquals(new BigDecimal("105"), stock.getQuantity());
    }
}
