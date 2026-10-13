package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.*;
import com.ruoyi.hospital.mapper.*;
import com.ruoyi.hospital.service.*;

class TcmInvoiceImportServiceTest {
    private final String id = "ad5c8e20-0938-4a6b-9a80-b1e053c4d320";
    private final TcmClinicSettingMapper settings = mock(TcmClinicSettingMapper.class);
    private final TcmInventoryItemMapper inventoryMapper = mock(TcmInventoryItemMapper.class);
    private final ITcmInventoryService inventory = mock(ITcmInventoryService.class);
    private final ITcmHerbDictService herbs = mock(ITcmHerbDictService.class);
    private TcmInvoiceImportService service;
    private TcmClinicSetting preview;

    @BeforeEach void setup() {
        service = new TcmInvoiceImportService();
        ReflectionTestUtils.setField(service, "settingMapper", settings);
        ReflectionTestUtils.setField(service, "inventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "inventoryService", inventory);
        ReflectionTestUtils.setField(service, "herbService", herbs);
        preview = new TcmClinicSetting(); preview.setSettingKey("inventoryInvoice:" + id);
        preview.setSettingValue(JSONObject.of("createdAt", System.currentTimeMillis()).toJSONString());
        when(settings.selectSettingForUpdate(preview.getSettingKey())).thenReturn(preview);
    }
    @Test void confirmedInvoiceAddsStockAndSetsOriginalCostAndDoubleSellingPrice() {
        TcmHerbDict herb = new TcmHerbDict(); herb.setId("h1"); herb.setName("通草"); herb.setIsActive(1);
        when(herbs.selectTcmHerbDictById("h1")).thenReturn(herb);
        TcmInventoryItem item = new TcmInventoryItem(); item.setId("i1"); item.setHerbDictId("h1");
        item.setCategory("powder"); item.setUnit("bag"); item.setIsActive(1); item.setQuantity(new BigDecimal("8"));
        when(inventoryMapper.selectTcmInventoryItemForUpdate("i1")).thenReturn(item);
        Map<String, Object> body = Map.of("invoiceId", id, "currency", "CAD", "items", List.of(Map.of(
            "inventoryId", "i1", "herbDictId", "h1", "category", "powder", "unit", "bag", "quantity", 3,
            "purchasePrice", 2.5, "gramsPerPacket", 10)));
        service.confirm(body);
        assertEquals(0, new BigDecimal("11").compareTo(item.getQuantity()));
        assertEquals(0, new BigDecimal("5").compareTo(item.getPricePerUnit()));
        assertEquals(0, new BigDecimal("2.5").compareTo(JSONObject.parseObject(item.getPayload()).getBigDecimal("purchasePrice")));
        service.confirm(body);
        verify(inventory, times(1)).updateTcmInventoryItem(item);
        assertEquals(0, new BigDecimal("11").compareTo(item.getQuantity()));
    }
    @Test void mismatchedCurrencyCannotUpdateStock() {
        assertThrows(ServiceException.class, () -> service.confirm(Map.of("invoiceId", id, "currency", "USD", "items", List.of())));
        verifyNoInteractions(inventory, herbs);
    }
    @Test void confirmedCostSurvivesTheRealInventoryUpdateService() {
        TcmInventoryServiceImpl realInventory = new TcmInventoryServiceImpl();
        ReflectionTestUtils.setField(realInventory, "inventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(realInventory, "herbDictService", herbs);
        ReflectionTestUtils.setField(service, "inventoryService", realInventory);
        TcmHerbDict herb = new TcmHerbDict(); herb.setId("h1"); herb.setName("通草"); herb.setIsActive(1);
        when(herbs.selectTcmHerbDictById("h1")).thenReturn(herb);
        TcmInventoryItem locked = new TcmInventoryItem(); locked.setId("i1"); locked.setHerbDictId("h1");
        locked.setCategory("powder"); locked.setUnit("bag"); locked.setIsActive(1); locked.setQuantity(new BigDecimal("8"));
        locked.setPayload("{\"notes\":\"keep supplier notes\",\"purchasePrice\":1}");
        TcmInventoryItem persisted = new TcmInventoryItem(); persisted.setCategory("powder"); persisted.setIsActive(1);
        persisted.setPayload(locked.getPayload());
        when(inventoryMapper.selectTcmInventoryItemForUpdate("i1")).thenReturn(locked);
        when(inventoryMapper.selectTcmInventoryItemById("i1")).thenReturn(persisted);

        service.confirm(Map.of("invoiceId", id, "currency", "CAD", "items", List.of(Map.of(
            "inventoryId", "i1", "herbDictId", "h1", "category", "powder", "unit", "bag", "quantity", 3, "purchasePrice", 2.5))));

        org.mockito.ArgumentCaptor<TcmInventoryItem> saved = org.mockito.ArgumentCaptor.forClass(TcmInventoryItem.class);
        verify(inventoryMapper).updateTcmInventoryItem(saved.capture());
        JSONObject payload = JSONObject.parseObject(saved.getValue().getPayload());
        assertEquals(new BigDecimal("2.5"), payload.getBigDecimal("purchasePrice"));
        assertEquals(id, payload.getString("lastInvoiceId"));
        assertEquals("keep supplier notes", payload.getString("notes"));
        assertEquals(0, new BigDecimal("11").compareTo(saved.getValue().getQuantity()));
        assertEquals(0, new BigDecimal("5").compareTo(saved.getValue().getPricePerUnit()));
    }
    @Test void importedInvoiceIsIdempotent() {
        preview.setSettingValue(JSONObject.of("imported", true).toJSONString());
        assertEquals(true, service.confirm(Map.of("invoiceId", id)).get("alreadyImported"));
        verifyNoInteractions(inventory, herbs, inventoryMapper);
    }

    @Test void legacyCategoryAndPacketAliasesMatchWithoutChangingUnitsOrQuantities() {
        TcmHerbDict herb = new TcmHerbDict(); herb.setId("h1"); herb.setIsActive(1);
        when(herbs.selectTcmHerbDictById("h1")).thenReturn(herb);
        TcmInventoryItem item = new TcmInventoryItem(); item.setId("i1"); item.setHerbDictId("h1");
        item.setCategory("Granules"); item.setUnit("bag (包)"); item.setIsActive(1); item.setQuantity(new BigDecimal("8"));
        when(inventoryMapper.selectTcmInventoryItemForUpdate("i1")).thenReturn(item);
        service.confirm(Map.of("invoiceId", id, "currency", "CAD", "items", List.of(Map.of(
            "inventoryId", "i1", "herbDictId", "h1", "category", "powder", "unit", "bag", "quantity", 600, "purchasePrice", 0.29))));
        verify(inventory).updateTcmInventoryItem(item);
        assertEquals(new BigDecimal("608"), item.getQuantity());
        assertEquals(new BigDecimal("0.58"), item.getPricePerUnit());
        assertEquals("bag (包)", item.getUnit());
    }

    @Test void uniquelyNamedLegacyStockGetsTheSameDictionaryBindingAsInventoryPreview() {
        TcmHerbDict herb = new TcmHerbDict(); herb.setId("h1"); herb.setName("通草"); herb.setIsActive(1);
        when(herbs.selectTcmHerbDictById("h1")).thenReturn(herb);
        when(herbs.selectTcmHerbDictList(any())).thenReturn(List.of(herb));
        TcmInventoryItem item = new TcmInventoryItem(); item.setId("i1"); item.setName("通草");
        item.setCategory("powder"); item.setUnit("bag"); item.setIsActive(1); item.setQuantity(BigDecimal.TEN);
        when(inventoryMapper.selectTcmInventoryItemForUpdate("i1")).thenReturn(item);
        service.confirm(Map.of("invoiceId", id, "currency", "CAD", "items", List.of(Map.of(
            "inventoryId", "i1", "herbDictId", "h1", "category", "powder", "unit", "bag", "quantity", 600, "purchasePrice", 0.29))));
        assertEquals("h1", item.getHerbDictId());
        assertEquals(new BigDecimal("610"), item.getQuantity());
        verify(inventory).updateTcmInventoryItem(item);
    }

    @Test void differentHerbCategoryOrMassUnitCannotBeImportedIntoSelectedStock() {
        TcmHerbDict herb = new TcmHerbDict(); herb.setId("h1"); herb.setIsActive(1);
        when(herbs.selectTcmHerbDictById("h1")).thenReturn(herb);
        for (String[] mismatch : new String[][] {{"h2", "powder", "bag"}, {"h1", "raw_herbs", "bag"}, {"h1", "powder", "g"}}) {
            TcmInventoryItem item = new TcmInventoryItem(); item.setId("i1"); item.setHerbDictId(mismatch[0]);
            item.setCategory(mismatch[1]); item.setUnit(mismatch[2]); item.setIsActive(1); item.setQuantity(BigDecimal.TEN);
            when(inventoryMapper.selectTcmInventoryItemForUpdate("i1")).thenReturn(item);
            assertThrows(ServiceException.class, () -> service.confirm(Map.of("invoiceId", id, "currency", "CAD", "items", List.of(Map.of(
                "inventoryId", "i1", "herbDictId", "h1", "category", "powder", "unit", "bag", "quantity", 600, "purchasePrice", 0.29)))));
            assertEquals(BigDecimal.TEN, item.getQuantity());
        }
        verifyNoInteractions(inventory);
    }
}
