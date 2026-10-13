package com.ruoyi.hospital.service.impl;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.hospital.utils.PayloadUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmConsultationMod;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.domain.TcmPatientFile;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmConsultationModMapper;
import com.ruoyi.hospital.mapper.TcmAppointmentMapper;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmInventoryService;
import com.ruoyi.hospital.service.ITcmPatientFileService;
import com.ruoyi.hospital.service.ITcmPdfService;

/**
 * 中医问诊 Service业务层处理
 *
 * @author ruoyi
 */
@Service
public class TcmConsultationServiceImpl implements ITcmConsultationService
{
    @Autowired
    private TcmConsultationMapper consultationMapper;

    @Autowired
    private TcmConsultationModMapper modMapper;

    @Autowired
    private TcmPatientMapper patientMapper;

    @Autowired
    private ITcmPdfService pdfService;

    @Autowired
    private ITcmPatientFileService patientFileService;

    @Autowired
    private ITcmInventoryService inventoryService;

    @Autowired
    private TcmAppointmentMapper appointmentMapper;

    @Autowired
    private ITcmAppointmentNotificationService appointmentNotificationService;

    private static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 查询问诊列表
     *
     * @param consultation 问诊查询条件
     * @return 问诊集合
     */
    @Override
    public List<TcmConsultation> selectTcmConsultationList(TcmConsultation consultation)
    {
        List<TcmConsultation> list = consultationMapper.selectTcmConsultationList(consultation);
        List<TcmConsultation> normalized = new ArrayList<>();
        for (TcmConsultation item : list)
        {
            normalized.add(prepareConsultationView(item));
        }
        return normalized;
    }

    /**
     * 查询问诊详情
     *
     * @param id 问诊ID
     * @return 问诊信息
     */
    @Override
    public TcmConsultation selectTcmConsultationById(String id)
    {
        return prepareConsultationView(selectStoredConsultation(id));
    }

    /**
     * 新增问诊
     *
     * @param consultation 问诊信息
     * @return 影响行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertTcmConsultation(TcmConsultation consultation)
    {
        if (consultation.getId() == null || consultation.getId().isEmpty())
        {
            consultation.setId(java.util.UUID.randomUUID().toString());
        }
        if (consultation.getConsultationId() == null || consultation.getConsultationId().isEmpty())
        {
            consultation.setConsultationId(generateConsultationId());
        }
        consultation.setVersion(1);
        consultation.setCreateTime(DateUtils.getNowDate());
        JSONObject payload = normalizeConsultationPayload(consultation, parsePayload(consultation.getPayload()));
        inheritPrescriptionInventoryState(payload, new JSONObject());
        rebuildReservationsForPayload(payload);
        consultation.setPayload(payload.toJSONString());
        HistorySourceContext historyContext = normalizeHistorySnapshot(consultation, null);
        int rows = consultationMapper.insertTcmConsultation(consultation);
        if (rows != 1) throw new ServiceException("问诊保存失败，库存变动已取消");
        syncConsultationFiles(consultation, payload);
        if (historyContext.isSourceConsultation())
        {
            propagateHistorySnapshot(consultation.getPatientId(), historyContext);
        }
        applyPrimaryPractitionerRules(consultation);
        return rows;
    }

    /**
     * 修改问诊
     *
     * @param consultation 问诊信息
     * @param actorId      操作者ID
     * @return 影响行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTcmConsultation(TcmConsultation consultation, String actorId)
    {
        TcmConsultation existing = selectStoredConsultation(consultation.getId(), true);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        consultation.setId(existing.getId());
        if (StringUtils.isNotEmpty(existing.getDeletedAt()))
        {
            throw new ServiceException("问诊记录已删除，请先恢复后再保存");
        }
        if (isConsultationLocked(existing))
        {
            throw new ServiceException("该问诊已完成并锁定，请先 Reactivate 后再修改");
        }
        consultation.setStatus(resolvePersistedStatus(existing.getStatus(), consultation.getStatus()));
        JSONObject existingPayload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        JSONObject payload = normalizeConsultationPayload(consultation, parsePayload(consultation.getPayload()));
        inheritPrescriptionInventoryState(payload, existingPayload);
        ensureDispensedPrescriptionsUnchanged(existingPayload, payload);
        if (hasInventoryRelevantPrescriptionChange(existingPayload, payload))
        {
            restoreReservationsFromPayload(existingPayload);
            rebuildReservationsForPayload(payload);
        }
        consultation.setPayload(payload.toJSONString());
        HistorySourceContext historyContext = normalizeHistorySnapshot(consultation, existing);
        int rows = consultationMapper.updateTcmConsultation(consultation);
        if (rows != 1) throw new ServiceException("问诊保存失败，库存变动已取消");
        syncConsultationFiles(consultation, payload);
        if (historyContext.isSourceConsultation())
        {
            propagateHistorySnapshot(consultation.getPatientId(), historyContext);
        }
        applyPrimaryPractitionerRules(consultation);
        return rows;
    }

    /**
     * 完成问诊
     *
     * @param id      问诊ID
     * @param actorId 操作者ID
     * @return 完成后的问诊对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation completeConsultation(String id, String actorId)
    {
        TcmConsultation existing = selectStoredConsultation(id, true);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        // 状态校验：只有草稿状态才能完成
        if (!"draft".equals(existing.getStatus()))
        {
            throw new ServiceException("仅草稿状态的问诊可以标记为完成，当前状态: " + existing.getStatus());
        }
        String operationTime = nowString();
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        payload.put("completedAt", operationTime);
        payload.put("completedBy", actorId);
        payload.put("reportVersion", existing.getVersion() != null ? existing.getVersion() : 1);
        payload.remove("reportPdfPath");
        payload.remove("reportPdfUrl");
        payload.remove("consultationPdfPath");
        payload.remove("consultationPdfUrl");
        appendPayloadModification(payload, "lock", "完成并锁定问诊", actorId, "问诊完成，生成当前版本PDF", operationTime);
        existing.setStatus("completed");
        existing.setLockedAt(operationTime);
        if (existing.getVersion() == null || existing.getVersion() < 1)
        {
            existing.setVersion(1);
        }
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        completeLinkedAppointment(existing, payload);

        // 记录完成审计日志（与 markPaid、markDispensingComplete 保持一致）
        insertConsultationMod(existing, actorId, "complete", "Consultation completed", "问诊完成", operationTime);
        try
        {
            pdfService.generateConsultationReport(existing.getId());
        }
        catch (Exception e)
        {
            throw new ServiceException("问诊已完成，但报告 PDF 生成失败: " + e.getMessage());
        }

        applyPrimaryPractitionerRules(existing);
        return consultationMapper.selectTcmConsultationById(existing.getId());
    }

    private void completeLinkedAppointment(TcmConsultation consultation, JSONObject payload)
    {
        TcmAppointment appointment = resolveLinkedAppointment(consultation, payload);
        if (appointment == null || !isCompletablePatientAppointment(appointment))
        {
            return;
        }
        appointment = appointmentMapper.selectTcmAppointmentForUpdate(appointment.getId());
        if (!matchesConsultationAppointment(consultation, appointment) || !isCompletablePatientAppointment(appointment))
        {
            return;
        }
        TcmAppointment before = copyAppointment(appointment);
        appointment.setStatus("completed");
        appointmentMapper.updateTcmAppointment(appointment);
        TcmAppointment updated = appointmentMapper.selectTcmAppointmentById(appointment.getId());
        appointmentNotificationService.handleAppointmentStatusChanged(before, updated != null ? updated : appointment);
    }

    private TcmAppointment resolveLinkedAppointment(TcmConsultation consultation, JSONObject payload)
    {
        String appointmentId = firstPayloadString(payload, "appointmentId", "sourceAppointmentId", "latestIntakeAppointmentId");
        if (StringUtils.isNotBlank(appointmentId))
        {
            TcmAppointment appointment = appointmentMapper.selectTcmAppointmentById(appointmentId);
            if (matchesConsultationAppointment(consultation, appointment))
            {
                return appointment;
            }
        }
        return findSingleSameDayAppointment(consultation);
    }

    private String firstPayloadString(JSONObject payload, String... keys)
    {
        if (payload == null || keys == null)
        {
            return null;
        }
        for (String key : keys)
        {
            String value = payload.getString(key);
            if (StringUtils.isNotBlank(value))
            {
                return value.trim();
            }
        }
        return null;
    }

    private TcmAppointment findSingleSameDayAppointment(TcmConsultation consultation)
    {
        if (consultation == null || StringUtils.isBlank(consultation.getPatientId()))
        {
            return null;
        }
        String consultDate = normalizedDatePrefix(consultation.getConsultDate());
        if (StringUtils.isBlank(consultDate))
        {
            return null;
        }
        TcmAppointment query = new TcmAppointment();
        query.setPatientId(consultation.getPatientId());
        List<TcmAppointment> appointments = appointmentMapper.selectTcmAppointmentList(query);
        List<TcmAppointment> candidates = new ArrayList<>();
        for (TcmAppointment appointment : appointments)
        {
            if (!isCompletablePatientAppointment(appointment))
            {
                continue;
            }
            if (matchesConsultationAppointment(consultation, appointment))
            {
                candidates.add(appointment);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private boolean matchesConsultationAppointment(TcmConsultation consultation, TcmAppointment appointment)
    {
        if (consultation == null || appointment == null
                || StringUtils.isBlank(consultation.getPatientId())
                || !StringUtils.equals(consultation.getPatientId(), appointment.getPatientId()))
        {
            return false;
        }
        String consultDate = normalizedDatePrefix(consultation.getConsultDate());
        if (StringUtils.isBlank(consultDate)
                || !StringUtils.equals(consultDate, normalizedDatePrefix(appointment.getStartTime())))
        {
            return false;
        }
        return StringUtils.isNotBlank(consultation.getPractitionerId())
                && StringUtils.equals(consultation.getPractitionerId(), appointment.getPractitionerId());
    }

    private String normalizedDatePrefix(String value)
    {
        if (StringUtils.isBlank(value))
        {
            return null;
        }
        String text = value.trim();
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    private boolean isCompletablePatientAppointment(TcmAppointment appointment)
    {
        if (appointment == null || StringUtils.isBlank(appointment.getId()))
        {
            return false;
        }
        String status = appointment.getStatus() == null ? "" : appointment.getStatus().trim().toLowerCase();
        if (!"booked".equals(status) && !"confirmed".equals(status))
        {
            return false;
        }
        return StringUtils.isNotBlank(appointment.getPatientId())
                && !"time_block".equals(appointment.getServiceType());
    }

    private TcmAppointment copyAppointment(TcmAppointment source)
    {
        TcmAppointment copy = new TcmAppointment();
        copy.setId(source.getId());
        copy.setPatientId(source.getPatientId());
        copy.setPractitionerId(source.getPractitionerId());
        copy.setRoomId(source.getRoomId());
        copy.setServiceType(source.getServiceType());
        copy.setStartTime(source.getStartTime());
        copy.setEndTime(source.getEndTime());
        copy.setStatus(source.getStatus());
        copy.setBranchId(source.getBranchId());
        copy.setIntakeToken(source.getIntakeToken());
        copy.setIntakeSubmitted(source.getIntakeSubmitted());
        copy.setPayload(source.getPayload());
        return copy;
    }

    private void applyPrimaryPractitionerRules(TcmConsultation source)
    {
        if (source == null
                || StringUtils.isBlank(source.getPatientId())
                || StringUtils.isBlank(source.getPractitionerId()))
        {
            return;
        }
        TcmPatient patient = patientMapper.selectTcmPatientById(source.getPatientId());
        if (patient == null)
        {
            return;
        }
        String nextPractitionerId = null;
        if (StringUtils.isBlank(patient.getPractitionerId()))
        {
            nextPractitionerId = source.getPractitionerId();
        }
        else
        {
            nextPractitionerId = practitionerAfterThreeConsecutiveVisits(source.getPatientId());
            if (StringUtils.equals(patient.getPractitionerId(), nextPractitionerId))
            {
                nextPractitionerId = null;
            }
        }
        if (StringUtils.isNotBlank(nextPractitionerId))
        {
            patient.setPractitionerId(nextPractitionerId);
            patientMapper.updateTcmPatient(patient);
        }
    }

    private String practitionerAfterThreeConsecutiveVisits(String patientId)
    {
        TcmConsultation query = new TcmConsultation();
        query.setPatientId(patientId);
        List<TcmConsultation> consultations = consultationMapper.selectTcmConsultationList(query);
        List<TcmConsultation> active = new ArrayList<>();
        for (TcmConsultation item : consultations)
        {
            if (item != null && StringUtils.isBlank(item.getDeletedAt()) && StringUtils.isNotBlank(item.getPractitionerId()))
            {
                active.add(item);
            }
        }
        active.sort((left, right) -> consultationSortKey(right).compareTo(consultationSortKey(left)));
        if (active.size() < 3)
        {
            return null;
        }
        String practitionerId = active.get(0).getPractitionerId();
        for (int i = 1; i < 3; i++)
        {
            if (!StringUtils.equals(practitionerId, active.get(i).getPractitionerId()))
            {
                return null;
            }
        }
        return practitionerId;
    }

    private String consultationSortKey(TcmConsultation consultation)
    {
        if (consultation == null)
        {
            return "";
        }
        if (StringUtils.isNotBlank(consultation.getConsultDate()))
        {
            return consultation.getConsultDate();
        }
        if (consultation.getCreateTime() != null)
        {
            return new SimpleDateFormat(DATETIME_FORMAT).format(consultation.getCreateTime());
        }
        return "";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation reactivateConsultation(String id, String actorId)
    {
        TcmConsultation existing = selectStoredConsultation(id, true);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        if (!isConsultationLocked(existing) && !"completed".equals(existing.getStatus()))
        {
            throw new ServiceException("仅已完成或已锁定的问诊可以 Reactivate");
        }

        String operationTime = nowString();
        int nextVersion = existing.getVersion() != null && existing.getVersion() > 0
                ? existing.getVersion() + 1
                : 2;
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        payload.put("reactivatedAt", operationTime);
        payload.put("reactivatedBy", actorId);
        payload.put("reactivatedFromVersion", existing.getVersion() != null ? existing.getVersion() : 1);
        appendPayloadModification(payload, "reactivate", "Reactivate", actorId, "重新激活问诊，进入 v" + nextVersion, operationTime);

        existing.setStatus("draft");
        existing.setLockedAt(null);
        existing.setVersion(nextVersion);
        existing.setPayload(payload.toJSONString());
        consultationMapper.reactivateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "reactivate", "Consultation reactivated", "进入 v" + nextVersion, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(existing.getId()));
    }

    private TcmConsultation selectStoredConsultation(String idOrConsultationId)
    {
        return selectStoredConsultation(idOrConsultationId, false);
    }

    private TcmConsultation selectStoredConsultation(String idOrConsultationId, boolean forUpdate)
    {
        if (StringUtils.isBlank(idOrConsultationId))
        {
            return null;
        }
        TcmConsultation consultation = consultationMapper.selectTcmConsultationById(idOrConsultationId);
        if (consultation == null)
        {
            consultation = consultationMapper.selectTcmConsultationByConsultationId(idOrConsultationId);
        }
        // Serialize the reservation read/restore/rebuild cycle for this consultation.
        return consultation != null && forUpdate
                ? consultationMapper.selectTcmConsultationForUpdate(consultation.getId()) : consultation;
    }

    /**
     * 标记已付款
     *
     * @param id          问诊ID
     * @param actorId     操作者ID
     * @param paymentInfo 收款信息
     * @return 标记后的问诊对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation markPaid(String id, String actorId, Map<String, Object> paymentInfo)
    {
        return recordPayment(id, actorId, paymentInfo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation updateInvoicePricing(String id, String actorId, Map<String, Object> pricingInfo)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        if (sumPaymentRecords(payload).compareTo(BigDecimal.ZERO) > 0)
        {
            throw new ServiceException("Invoice pricing cannot be changed after payment");
        }

        Map<String, Object> safePricingInfo = pricingInfo != null ? pricingInfo : new LinkedHashMap<>();
        copyInvoicePricingFields(payload, safePricingInfo);
        normalizePaymentState(payload);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation syncPrescription(String id, Map<String, Object> prescriptionData, String actorId)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        Map<String, Object> body = prescriptionData != null ? prescriptionData : new LinkedHashMap<>();
        savePrescriptionDraftContext(existing, payload, body);
        Map<String, Object> incomingPrescription = extractPrescriptionPayload(body);
        if (incomingPrescription.isEmpty())
        {
            throw new ServiceException("处方内容不能为空");
        }

        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        String prescriptionId = getString(incomingPrescription, "id", null);
        if (prescriptionId == null || prescriptionId.isEmpty())
        {
            prescriptionId = buildPrescriptionId(existing.getId(), prescriptions.size());
        }

        int index = findPrescriptionIndex(prescriptions, prescriptionId);
        Map<String, Object> current = index >= 0 ? prescriptions.get(index) : null;
        ensurePrescriptionCanEdit(current);

        Map<String, Object> nextPrescription = buildWritablePrescription(existing, incomingPrescription, current, prescriptionId);
        List<Map<String, Object>> reservation;
        if (canReuseReservation(current, nextPrescription))
        {
            reservation = toMapList(current.get("inventoryReservation"));
        }
        else
        {
            restoreReservationIfNeeded(current);
            reservation = reservePrescription(nextPrescription);
        }
        nextPrescription.put("inventoryReservation", reservation);
        nextPrescription.put("inventorySyncedAt", nowString());
        if (index >= 0)
        {
            prescriptions.set(index, nextPrescription);
        }
        else
        {
            prescriptions.add(nextPrescription);
        }

        payload.put("prescriptions", prescriptions);
        applyTotals(payload, body);
        syncPrimaryPrescriptionFields(payload);
        normalizePaymentState(payload);
        persistConsultationPayload(existing, payload);

        String operationTime = nowString();
        String changeSummary = nextPrescription.get("formulaName") != null
                ? "同步处方：" + nextPrescription.get("formulaName")
                : "同步处方：" + prescriptionId;
        appendPayloadModification(payload, "prescription", "同步处方库存", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "prescription", "Prescription synced", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation completePrescription(String id, String prescriptionId, Map<String, Object> payloadBody, String actorId)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        Map<String, Object> prescription = findPrescriptionOrThrow(prescriptions, prescriptionId);
        if (isPrescriptionDeleted(prescription))
        {
            throw new ServiceException("处方已删除");
        }
        if ("dispensed".equals(resolvePrescriptionStatus(prescription, existing.getStatus())))
        {
            // A delayed completion retry must not reopen an already dispensed prescription.
            return prepareConsultationView(existing);
        }
        ensureReservationExists(prescription);
        prescription.put("rxStatus", "pending");
        prescription.put("dispensingCompleted", false);
        prescription.remove("dispensingCompletedAt");
        prescription.remove("dispensedBy");
        applyTotals(payload, payloadBody);
        payload.put("prescriptions", prescriptions);
        syncPrimaryPrescriptionFields(payload);
        normalizePaymentState(payload);
        persistConsultationPayload(existing, payload);

        String operationTime = nowString();
        String changeSummary = "处方进入待发：" + getString(prescription, "formulaName", prescriptionId);
        appendPayloadModification(payload, "prescription", "完成处方", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "prescription", "Prescription completed", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation dispensePrescription(String id, String prescriptionId, String actorId)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        Map<String, Object> prescription = findPrescriptionOrThrow(prescriptions, prescriptionId);
        String rxStatus = resolvePrescriptionStatus(prescription, existing.getStatus());
        if (!"pending".equals(rxStatus))
        {
            throw new ServiceException("仅待发处方可以发药");
        }

        String operationTime = nowString();
        prescription.put("rxStatus", "dispensed");
        prescription.put("dispensingCompleted", true);
        prescription.put("dispensingCompletedAt", operationTime);
        prescription.put("dispensedBy", actorId);
        payload.put("prescriptions", prescriptions);
        payload.put("dispensingCompleted", hasAnyDispensedPrescription(prescriptions, existing.getStatus()));
        persistConsultationPayload(existing, payload);

        String changeSummary = "处方已发：" + getString(prescription, "formulaName", prescriptionId);
        appendPayloadModification(payload, "dispense", "完成发药", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "dispense", "Prescription dispensed", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation reopenPrescription(String id, String prescriptionId, String actorId)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        Map<String, Object> prescription = findPrescriptionOrThrow(prescriptions, prescriptionId);
        String rxStatus = resolvePrescriptionStatus(prescription, existing.getStatus());
        if (!"dispensed".equals(rxStatus))
        {
            throw new ServiceException("仅已发处方可以回退");
        }

        prescription.put("rxStatus", "pending");
        prescription.put("dispensingCompleted", false);
        prescription.remove("dispensingCompletedAt");
        prescription.remove("dispensedBy");
        payload.put("prescriptions", prescriptions);
        payload.put("dispensingCompleted", hasAnyDispensedPrescription(prescriptions, existing.getStatus()));
        normalizePaymentState(payload);
        persistConsultationPayload(existing, payload);

        String operationTime = nowString();
        String changeSummary = "已回退处方：" + getString(prescription, "formulaName", prescriptionId);
        appendPayloadModification(payload, "prescription", "回退处方", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "prescription", "Prescription reopened", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation deletePrescription(String id, String prescriptionId, Map<String, Object> payloadBody, String actorId)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        Map<String, Object> prescription = findPrescriptionOrThrow(prescriptions, prescriptionId);
        String rxStatus = resolvePrescriptionStatus(prescription, existing.getStatus());
        if ("dispensed".equals(rxStatus))
        {
            throw new ServiceException("已发处方请先回退后再删除");
        }

        if (isPrescriptionDeleted(prescription) && !isDeletedPrescriptionHoldingInventory(prescription))
        {
            return prepareConsultationView(existing);
        }
        restoreReservationIfNeeded(prescription);
        String operationTime = nowString();
        prescription.put("deletedAt", operationTime);
        prescription.put("deletedBy", actorId);
        prescription.put("inventoryReservation", new ArrayList<>());
        prescription.put("inventoryActionPending", false);
        prescription.put("inventoryReleasedAt", operationTime);
        prescription.put("inventorySyncedAt", null);
        applyTotals(payload, payloadBody);
        payload.put("prescriptions", prescriptions);
        syncPrimaryPrescriptionFields(payload);
        normalizePaymentState(payload);
        persistConsultationPayload(existing, payload);

        String changeSummary = "已删除处方：" + getString(prescription, "formulaName", prescriptionId);
        appendPayloadModification(payload, "prescription", "删除处方", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "prescription", "Prescription deleted", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> listDeletedPrescriptions()
    {
        TcmConsultation query = new TcmConsultation();
        query.setDeletedAt("ANY");
        List<TcmConsultation> consultations = consultationMapper.selectTcmConsultationList(query);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TcmConsultation consultation : consultations)
        {
            JSONObject payload = normalizeConsultationPayload(consultation, parsePayload(consultation.getPayload()));
            for (Map<String, Object> prescription : toMapList(payload.get("prescriptions")))
            {
                if (!isPrescriptionDeleted(prescription))
                {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", getString(prescription, "id", null));
                row.put("consultationRecordId", consultation.getId());
                row.put("consultationId", consultation.getConsultationId());
                row.put("patientId", consultation.getPatientId());
                row.put("formulaName", getString(prescription, "formulaName", ""));
                row.put("prescriptionType", getString(prescription, "prescriptionType", ""));
                row.put("deletedAt", getString(prescription, "deletedAt", ""));
                row.put("rxStatus", resolvePrescriptionStatus(prescription, consultation.getStatus()));
                row.put("subtotal", prescription.get("subtotal"));
                row.put("quantity", prescription.get("quantity"));
                row.put("inventoryHeld", !toMapList(prescription.get("inventoryReservation")).isEmpty());
                row.put("inventoryActionPending", toBoolean(prescription.get("inventoryActionPending")));
                row.put("consultationDeletedAt", consultation.getDeletedAt());
                rows.add(row);
            }
        }
        rows.sort((a, b) -> String.valueOf(b.get("deletedAt")).compareTo(String.valueOf(a.get("deletedAt"))));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation restoreDeletedPrescription(String id, String prescriptionId, String actorId)
    {
        TcmConsultation existing = requireEditableConsultation(id);
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        Map<String, Object> prescription = findPrescriptionOrThrow(prescriptions, prescriptionId);
        if (!isPrescriptionDeleted(prescription))
        {
            throw new ServiceException("处方未被删除");
        }
        prescription.remove("deletedAt");
        prescription.remove("deletedBy");
        prescription.remove("inventoryReleasedAt");
        prescription.remove("inventoryActionPending");
        if (toMapList(prescription.get("inventoryReservation")).isEmpty()
                && shouldSyncLifecycleReservation(prescription, existing.getStatus()))
        {
            List<Map<String, Object>> reservation = reservePrescription(prescription);
            prescription.put("inventoryReservation", reservation);
            prescription.put("inventorySyncedAt", nowString());
        }
        payload.put("prescriptions", prescriptions);
        syncPrimaryPrescriptionFields(payload);
        normalizePaymentState(payload);
        persistConsultationPayload(existing, payload);

        String operationTime = nowString();
        String changeSummary = "已恢复处方：" + getString(prescription, "formulaName", prescriptionId);
        appendPayloadModification(payload, "prescription", "恢复处方", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "prescription", "Prescription restored", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation permanentlyDeletePrescription(String id, String prescriptionId, boolean restoreInventory, String actorId)
    {
        // Recycle-bin cleanup must also work after the parent consultation is soft-deleted.
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        int index = findPrescriptionIndex(prescriptions, prescriptionId);
        if (index < 0)
        {
            throw new ServiceException("处方不存在");
        }
        Map<String, Object> prescription = prescriptions.get(index);
        if (!isPrescriptionDeleted(prescription))
        {
            throw new ServiceException("请先删除处方到回收站，再永久删除");
        }
        if (restoreInventory)
        {
            restoreReservationIfNeeded(prescription);
        }
        prescriptions.remove(index);
        payload.put("prescriptions", prescriptions);
        syncPrimaryPrescriptionFields(payload);
        normalizePaymentState(payload);
        persistConsultationPayload(existing, payload);

        String operationTime = nowString();
        String changeSummary = restoreInventory
                ? "永久删除处方并恢复库存：" + getString(prescription, "formulaName", prescriptionId)
                : "永久删除处方且不修改库存：" + getString(prescription, "formulaName", prescriptionId);
        appendPayloadModification(payload, "prescription", "永久删除处方", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "prescription", "Prescription permanently deleted", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation recordPayment(String id, String actorId, Map<String, Object> paymentInfo)
    {
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        if ("draft".equals(existing.getStatus()))
        {
            throw new ServiceException("草稿问诊不能收款");
        }

        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        Map<String, Object> safePaymentInfo = paymentInfo != null ? paymentInfo : new LinkedHashMap<>();
        BigDecimal totalAmount = toBigDecimal(payload.get("totalAmount"));
        BigDecimal paidAmount = sumPaymentRecords(payload);
        BigDecimal outstanding = totalAmount.subtract(paidAmount);
        if (outstanding.compareTo(BigDecimal.ZERO) < 0)
        {
            outstanding = BigDecimal.ZERO;
        }
        if (outstanding.compareTo(BigDecimal.ZERO) == 0)
        {
            normalizePaymentState(payload);
            existing.setPayload(payload.toJSONString());
            consultationMapper.updateTcmConsultation(existing);
            return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
        }

        String operationTime = nowString();
        JSONArray paymentRecords = payload.getJSONArray("paymentRecords");
        if (paymentRecords == null)
        {
            paymentRecords = new JSONArray();
        }
        JSONObject paymentRecord = new JSONObject();
        paymentRecord.put("id", "pay-" + System.currentTimeMillis());
        paymentRecord.put("date", operationTime);
        paymentRecord.put("amount", outstanding);
        paymentRecord.put("method", getString(safePaymentInfo, "paymentMethod", "manual"));
        putIfPresent(paymentRecord, "reference", safePaymentInfo.get("paymentReference"));
        putIfPresent(paymentRecord, "note", safePaymentInfo.get("paymentNote"));
        paymentRecord.put("actorId", actorId);
        paymentRecords.add(paymentRecord);
        payload.put("paymentRecords", paymentRecords);
        payload.put("paidAt", operationTime);
        payload.put("paidBy", actorId);
        payload.put("paymentMethod", paymentRecord.getString("method"));
        putIfPresent(payload, "paymentReference", paymentRecord.get("reference"));
        putIfPresent(payload, "paymentNote", paymentRecord.get("note"));
        normalizePaymentState(payload);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);

        Map<String, String> invoice = pdfService.generateInvoice(id);
        putIfPresent(payload, "invoicePdfUrl", invoice.get("url"));
        putIfPresent(payload, "invoicePdfPath", invoice.get("filePath"));
        payload.put("invoiceGeneratedAt", operationTime);
        normalizePaymentState(payload);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);

        String changeSummary = buildPaymentSummary(payload);
        appendPayloadModification(payload, "payment", "记录付款", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "payment", "Payment recorded", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation recordProviderPayment(String id, String actorId, Map<String, Object> paymentInfo)
    {
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        Map<String, Object> safePaymentInfo = paymentInfo != null ? paymentInfo : new LinkedHashMap<>();
        String provider = getString(safePaymentInfo, "provider", "stripe");
        String sessionId = getString(safePaymentInfo, "stripeSessionId", null);
        String paymentIntentId = getString(safePaymentInfo, "stripePaymentIntentId", null);

        JSONArray paymentRecords = payload.getJSONArray("paymentRecords");
        if (paymentRecords == null)
        {
            paymentRecords = new JSONArray();
        }
        for (int i = 0; i < paymentRecords.size(); i++)
        {
            JSONObject record = paymentRecords.getJSONObject(i);
            if (record == null) continue;
            if ((StringUtils.isNotBlank(sessionId) && sessionId.equals(record.getString("stripeSessionId")))
                    || (StringUtils.isNotBlank(paymentIntentId) && paymentIntentId.equals(record.getString("stripePaymentIntentId"))))
            {
                return prepareConsultationView(existing);
            }
        }

        BigDecimal amount = toBigDecimal(safePaymentInfo.get("amount"));
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            amount = toBigDecimal(payload.get("totalAmount")).subtract(sumPaymentRecords(payload));
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            return prepareConsultationView(existing);
        }

        String operationTime = nowString();
        JSONObject paymentRecord = new JSONObject();
        paymentRecord.put("id", "pay-" + provider + "-" + System.currentTimeMillis());
        paymentRecord.put("date", operationTime);
        paymentRecord.put("amount", amount);
        paymentRecord.put("currency", getString(safePaymentInfo, "currency", getString(payload, "currency", "CAD")));
        paymentRecord.put("method", getString(safePaymentInfo, "paymentMethod", "card"));
        paymentRecord.put("provider", provider);
        putIfPresent(paymentRecord, "stripeSessionId", sessionId);
        putIfPresent(paymentRecord, "stripePaymentIntentId", paymentIntentId);
        putIfPresent(paymentRecord, "stripeEventId", safePaymentInfo.get("stripeEventId"));
        putIfPresent(paymentRecord, "providerStatus", safePaymentInfo.get("providerStatus"));
        putIfPresent(paymentRecord, "livemode", safePaymentInfo.get("livemode"));
        paymentRecord.put("actorId", actorId);
        paymentRecords.add(paymentRecord);

        payload.put("paymentRecords", paymentRecords);
        payload.put("paidAt", operationTime);
        payload.put("paidBy", actorId);
        payload.put("paymentMethod", paymentRecord.getString("method"));
        normalizePaymentState(payload);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        Map<String, String> invoice = pdfService.generateInvoice(id);
        putIfPresent(payload, "invoicePdfUrl", invoice.get("url"));
        putIfPresent(payload, "invoicePdfPath", invoice.get("filePath"));
        payload.put("invoiceGeneratedAt", operationTime);
        normalizePaymentState(payload);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);

        String changeSummary = buildPaymentSummary(payload);
        appendPayloadModification(payload, "payment", "Stripe付款", actorId, changeSummary, operationTime);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        insertConsultationMod(existing, actorId, "payment", "Stripe payment recorded", changeSummary, operationTime);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    /**
     * 标记配药完成
     *
     * @param id      问诊ID
     * @param actorId 操作者ID
     * @return 标记后的问诊对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation markDispensingComplete(String id, String actorId)
    {
        return markDispensingComplete(id, actorId, false);
    }

    /**
     * 标记配药完成（可跳过库存扣减）
     * 当前主流程会在处方同步/完成时预占库存，发药时通常只做状态流转。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation markDispensingComplete(String id, String actorId, boolean skipDeduct)
    {
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }

        JSONObject payload = normalizeConsultationPayload(existing, parsePayload(existing.getPayload()));
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        boolean updated = false;
        for (Map<String, Object> prescription : prescriptions)
        {
            if ("pending".equals(resolvePrescriptionStatus(prescription, existing.getStatus())))
            {
                prescription.put("rxStatus", "dispensed");
                prescription.put("dispensingCompleted", true);
                prescription.put("dispensingCompletedAt", nowString());
                prescription.put("dispensedBy", actorId);
                updated = true;
            }
        }
        if (!updated)
        {
            return prepareConsultationView(existing);
        }

        payload.put("prescriptions", prescriptions);
        payload.put("dispensingCompleted", true);
        normalizePaymentState(payload);
        existing.setPayload(payload.toJSONString());
        consultationMapper.updateTcmConsultation(existing);
        return prepareConsultationView(consultationMapper.selectTcmConsultationById(id));
    }

    /**
     * 软删除问诊
     *
     * @param id 问诊ID
     * @return 软删除后的问诊对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation softDeleteTcmConsultation(String id)
    {
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        if (StringUtils.isNotEmpty(existing.getDeletedAt())) return prepareConsultationView(existing);
        JSONObject payload = parsePayload(existing.getPayload());
        releaseLifecycleReservations(payload, existing.getStatus());
        existing.setPayload(payload.toJSONString());
        existing.setDeletedAt(nowString());
        consultationMapper.updateTcmConsultation(existing);
        return prepareConsultationView(existing);
    }

    /**
     * 恢复已软删除的问诊
     *
     * @param id 问诊ID
     * @return 恢复后的问诊对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmConsultation restoreTcmConsultation(String id)
    {
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        if (StringUtils.isEmpty(existing.getDeletedAt()))
        {
            return prepareConsultationView(existing);
        }
        JSONObject payload = parsePayload(existing.getPayload());
        rebuildLifecycleReservations(payload, existing.getStatus());
        existing.setPayload(payload.toJSONString());
        existing.setDeletedAt(null);
        consultationMapper.updateTcmConsultation(existing);
        return prepareConsultationView(existing);
    }

    /**
     * 硬删除问诊
     *
     * @param id 问诊ID
     * @return 影响行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int hardDeleteTcmConsultation(String id)
    {
        TcmConsultation existing = consultationMapper.selectTcmConsultationForUpdate(id);
        if (existing == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        if (existing.getDeletedAt() == null || existing.getDeletedAt().isEmpty())
        {
            throw new ServiceException("该记录未被软删除，无法物理删除");
        }
        // Legacy recycle-bin records can still hold reservations. Release those
        // in the same transaction as the final removal; released rows are a no-op.
        restoreReservationsFromPayload(parsePayload(existing.getPayload()));
        int rows = consultationMapper.deleteTcmConsultationById(id);
        if (rows != 1) throw new ServiceException("问诊删除失败，库存变动已取消");
        return rows;
    }

    private TcmConsultation prepareConsultationView(TcmConsultation consultation)
    {
        if (consultation == null)
        {
            return null;
        }
        JSONObject payload = normalizeConsultationPayload(consultation, parsePayload(consultation.getPayload()));
        consultation.setPayload(payload.toJSONString());
        return consultation;
    }

    private TcmConsultation requireEditableConsultation(String id)
    {
        TcmConsultation consultation = consultationMapper.selectTcmConsultationForUpdate(id);
        if (consultation == null)
        {
            throw new ServiceException("问诊记录不存在");
        }
        if (consultation.getDeletedAt() != null && !consultation.getDeletedAt().isEmpty())
        {
            throw new ServiceException("问诊记录已删除");
        }
        return consultation;
    }

    private JSONObject normalizeConsultationPayload(TcmConsultation consultation, JSONObject payload)
    {
        JSONObject normalized = payload != null ? payload : new JSONObject();
        List<Map<String, Object>> prescriptions = toMapList(normalized.get("prescriptions"));
        if (prescriptions.isEmpty())
        {
            Map<String, Object> legacyPrescription = buildLegacyPrescription(consultation, normalized);
            if (!legacyPrescription.isEmpty())
            {
                prescriptions.add(legacyPrescription);
            }
        }

        List<Map<String, Object>> normalizedPrescriptions = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> prescription : prescriptions)
        {
            normalizedPrescriptions.add(normalizePrescriptionEntry(consultation, prescription, index));
            index++;
        }
        normalized.put("prescriptions", normalizedPrescriptions);
        syncPrimaryPrescriptionFields(normalized);

        if (normalized.get("paymentRecords") == null && "paid".equals(consultation.getStatus()))
        {
            JSONArray paymentRecords = new JSONArray();
            JSONObject paymentRecord = new JSONObject();
            paymentRecord.put("id", "legacy-payment-" + safeString(consultation.getId()));
            paymentRecord.put("date", firstNonBlank(
                    normalized.getString("paidAt"),
                    consultation.getLockedAt(),
                    consultation.getConsultDate(),
                    nowString()));
            paymentRecord.put("amount", toBigDecimal(normalized.get("totalAmount")));
            paymentRecord.put("method", firstNonBlank(normalized.getString("paymentMethod"), "legacy"));
            paymentRecords.add(paymentRecord);
            normalized.put("paymentRecords", paymentRecords);
        }

        normalizePaymentState(normalized);
        normalized.put("dispensingCompleted", hasAnyDispensedPrescription(normalizedPrescriptions, consultation.getStatus()));
        return normalized;
    }

    private Map<String, Object> buildLegacyPrescription(TcmConsultation consultation, JSONObject payload)
    {
        List<Map<String, Object>> herbals = toMapList(payload.get("herbals"));
        if (herbals.isEmpty())
        {
            return new LinkedHashMap<>();
        }
        String prescriptionType = payload.getString("prescriptionType");
        if (prescriptionType == null || prescriptionType.isEmpty() || "none".equals(prescriptionType))
        {
            return new LinkedHashMap<>();
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("id", buildPrescriptionId(consultation.getId(), 0));
        legacy.put("formulaName", payload.getString("formulaName"));
        legacy.put("prescriptionType", prescriptionType);
        legacy.put("quantity", toBigDecimal(payload.get("quantity")).compareTo(BigDecimal.ZERO) > 0 ? payload.get("quantity") : 1);
        legacy.put("direction", payload.getString("direction"));
        legacy.put("whereToGet", payload.getString("whereToGet"));
        legacy.put("preferredUnit", payload.getString("preferredUnit"));
        legacy.put("items", herbals);
        legacy.put("subtotal", payload.get("totalRxAmount"));
        legacy.put("dispensingCompleted", payload.getBooleanValue("dispensingCompleted"));
        return legacy;
    }

    private Map<String, Object> normalizePrescriptionEntry(TcmConsultation consultation, Map<String, Object> prescription, int index)
    {
        Map<String, Object> normalized = new LinkedHashMap<>(prescription != null ? prescription : new LinkedHashMap<>());
        String prescriptionId = getString(normalized, "id", null);
        if (prescriptionId == null || prescriptionId.isEmpty())
        {
            prescriptionId = buildPrescriptionId(consultation.getId(), index);
        }
        normalized.put("id", prescriptionId);
        List<Map<String, Object>> items = toMapList(normalized.get("items"));
        normalized.put("items", items);
        String rxStatus = resolvePrescriptionStatus(normalized, consultation.getStatus());
        normalized.put("rxStatus", rxStatus);
        normalized.put("dispensingCompleted", "dispensed".equals(rxStatus));
        if ("dispensed".equals(rxStatus) && normalized.get("dispensingCompletedAt") == null)
        {
            putIfPresent(normalized, "dispensingCompletedAt", firstNonBlank(
                    getString(normalized, "dispensingCompletedAt", null),
                    consultation.getLockedAt(),
                    consultation.getConsultDate()));
        }
        if (normalized.get("inventoryReservation") == null)
        {
            normalized.put("inventoryReservation", new ArrayList<>());
        }
        return normalized;
    }

    private String buildPrescriptionId(String consultationId, int index)
    {
        return "rx-" + safeString(consultationId) + "-" + index;
    }

    private String resolvePrescriptionStatus(Map<String, Object> prescription, String consultationStatus)
    {
        String rxStatus = getString(prescription, "rxStatus", null);
        if (rxStatus != null && !rxStatus.isEmpty())
        {
            return rxStatus;
        }
        if (toBoolean(prescription.get("dispensingCompleted")))
        {
            return "dispensed";
        }
        if ("paid".equals(consultationStatus))
        {
            return "pending";
        }
        return "editing";
    }

    private Map<String, Object> extractPrescriptionPayload(Map<String, Object> body)
    {
        if (body == null)
        {
            return new LinkedHashMap<>();
        }
        Object prescription = body.get("prescription");
        if (prescription instanceof Map<?, ?>)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapped = (Map<String, Object>) prescription;
            return new LinkedHashMap<>(mapped);
        }
        return new LinkedHashMap<>(body);
    }

    private Map<String, Object> buildWritablePrescription(
            TcmConsultation consultation,
            Map<String, Object> incomingPrescription,
            Map<String, Object> current,
            String prescriptionId)
    {
        Map<String, Object> nextPrescription = new LinkedHashMap<>();
        if (current != null)
        {
            nextPrescription.putAll(current);
        }
        nextPrescription.putAll(incomingPrescription);
        nextPrescription.put("id", prescriptionId);
        nextPrescription.put("items", toMapList(incomingPrescription.get("items")));
        nextPrescription.put("rxStatus", "editing");
        nextPrescription.put("dispensingCompleted", false);
        nextPrescription.remove("dispensingCompletedAt");
        nextPrescription.remove("dispensedBy");
        nextPrescription.remove("deletedAt");
        if (nextPrescription.get("quantity") == null || toBigDecimal(nextPrescription.get("quantity")).compareTo(BigDecimal.ZERO) <= 0)
        {
            nextPrescription.put("quantity", BigDecimal.ONE);
        }
        if (nextPrescription.get("prescriptionType") == null || String.valueOf(nextPrescription.get("prescriptionType")).trim().isEmpty())
        {
            nextPrescription.put("prescriptionType", firstNonBlank(
                    consultation != null ? parsePayload(consultation.getPayload()).getString("prescriptionType") : null,
                    "raw_herbs"));
        }
        return nextPrescription;
    }

    private void applyTotals(JSONObject payload, Map<String, Object> body)
    {
        if (body == null)
        {
            return;
        }
        Map<String, Object> totals = null;
        Object totalsObject = body.get("totals");
        if (totalsObject instanceof Map<?, ?>)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapped = (Map<String, Object>) totalsObject;
            totals = mapped;
        }
        if (totals == null)
        {
            totals = body;
        }
        if (totals.containsKey("totalAmount"))
        {
            payload.put("totalAmount", totals.get("totalAmount"));
        }
        if (totals.containsKey("taxAmount"))
        {
            payload.put("taxAmount", totals.get("taxAmount"));
        }
        if (totals.containsKey("totalWithoutTax"))
        {
            payload.put("totalWithoutTax", totals.get("totalWithoutTax"));
        }
        if (totals.containsKey("includeRxAmount"))
        {
            payload.put("includeRxAmount", totals.get("includeRxAmount"));
        }
    }

    private void copyInvoicePricingFields(JSONObject payload, Map<String, Object> source)
    {
        if (payload == null || source == null)
        {
            return;
        }
        String[] keys = {
                "consultationFee",
                "services",
                "discountType",
                "discountValue",
                "taxable",
                "includeRxAmount",
                "add3rdParty",
                "currency",
                "comments",
                "overrideTaxRate",
                "totalWithoutTax",
                "taxAmount",
                "totalAmount"
        };
        for (String key : keys)
        {
            if (source.containsKey(key))
            {
                payload.put(key, source.get(key));
            }
        }
    }

    private void persistConsultationPayload(TcmConsultation consultation, JSONObject payload)
    {
        normalizePaymentState(payload);
        consultation.setPayload(payload.toJSONString());
        if ("paid".equals(consultation.getStatus()))
        {
            consultation.setStatus("completed");
        }
        consultationMapper.updateTcmConsultation(consultation);
        syncConsultationFiles(consultation, payload);
    }

    private void syncConsultationFiles(TcmConsultation consultation, JSONObject payload)
    {
        if (consultation == null || payload == null || StringUtils.isEmpty(consultation.getPatientId()))
        {
            return;
        }
        List<FileRef> refs = collectFileRefs(payload);
        for (FileRef ref : refs)
        {
            if (StringUtils.isEmpty(ref.path))
            {
                continue;
            }
            TcmPatientFile existing = patientFileService.selectTcmPatientFileByPath(ref.path);
            if (existing != null)
            {
                boolean changed = false;
                if (StringUtils.isEmpty(existing.getPatientId()))
                {
                    existing.setPatientId(consultation.getPatientId());
                    changed = true;
                }
                if (StringUtils.isEmpty(existing.getConsultationId()))
                {
                    existing.setConsultationId(consultation.getId());
                    changed = true;
                }
                if (changed)
                {
                    patientFileService.updateTcmPatientFile(existing);
                }
                continue;
            }
            TcmPatientFile file = new TcmPatientFile();
            file.setPatientId(consultation.getPatientId());
            file.setConsultationId(consultation.getId());
            file.setFileType(ref.type);
            file.setFileName(ref.name);
            file.setFilePath(ref.path);
            patientFileService.insertTcmPatientFile(file);
        }
    }

    private List<FileRef> collectFileRefs(Object value)
    {
        List<FileRef> refs = new ArrayList<>();
        collectFileRefs(value, "document", refs, new LinkedHashSet<>());
        return refs;
    }

    @SuppressWarnings("unchecked")
    private void collectFileRefs(Object value, String typeHint, List<FileRef> refs, Set<String> seen)
    {
        if (value instanceof JSONObject)
        {
            JSONObject obj = (JSONObject) value;
            String currentType = resolveFileType(typeHint, obj);
            String path = normalizeFilePath(firstNonBlank(
                    obj.getString("resource"),
                    obj.getString("filePath"),
                    obj.getString("path"),
                    obj.getString("url")));
            if (StringUtils.isNotEmpty(path) && seen.add(path))
            {
                refs.add(new FileRef(path, currentType, firstNonBlank(
                        obj.getString("fileName"),
                        obj.getString("name"),
                        fileNameFromPath(path))));
            }
            for (Map.Entry<String, Object> entry : obj.entrySet())
            {
                collectFileRefs(entry.getValue(), typeFromKey(entry.getKey(), currentType), refs, seen);
            }
            return;
        }
        if (value instanceof Map<?, ?>)
        {
            collectFileRefs(new JSONObject((Map<String, Object>) value), typeHint, refs, seen);
            return;
        }
        if (value instanceof List<?>)
        {
            for (Object item : (List<?>) value)
            {
                collectFileRefs(item, typeHint, refs, seen);
            }
        }
    }

    private String resolveFileType(String typeHint, JSONObject obj)
    {
        String explicit = firstNonBlank(obj.getString("fileType"), obj.getString("type"));
        if (StringUtils.isNotEmpty(explicit) && !looksLikePath(explicit))
        {
            return explicit;
        }
        return typeHint;
    }

    private String typeFromKey(String key, String fallback)
    {
        String normalized = key != null ? key.toLowerCase() : "";
        if (normalized.contains("tongue"))
        {
            return "tongue_image";
        }
        if (normalized.contains("invoice"))
        {
            return "invoice_pdf";
        }
        if (normalized.contains("report"))
        {
            return "consultation_report_pdf";
        }
        if (normalized.contains("consent"))
        {
            return "consent_pdf";
        }
        return fallback != null ? fallback : "document";
    }

    private String normalizeFilePath(String raw)
    {
        if (StringUtils.isEmpty(raw))
        {
            return null;
        }
        String value = raw.trim();
        int resourceIndex = value.indexOf("resource=");
        if (resourceIndex >= 0)
        {
            String resource = value.substring(resourceIndex + "resource=".length());
            int amp = resource.indexOf('&');
            if (amp >= 0)
            {
                resource = resource.substring(0, amp);
            }
            value = URLDecoder.decode(resource, StandardCharsets.UTF_8);
        }
        if (!looksLikePath(value))
        {
            return null;
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private boolean looksLikePath(String value)
    {
        if (StringUtils.isEmpty(value))
        {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("/")
                && (lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".pdf")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif"));
    }

    private String fileNameFromPath(String path)
    {
        if (StringUtils.isEmpty(path))
        {
            return "document";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private void syncPrimaryPrescriptionFields(JSONObject payload)
    {
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        Map<String, Object> primary = null;
        for (Map<String, Object> prescription : prescriptions)
        {
            if (isPrescriptionDeleted(prescription))
            {
                continue;
            }
            primary = prescription;
            break;
        }

        if (primary == null)
        {
            payload.put("herbals", new ArrayList<>());
            payload.put("formulaName", "");
            payload.put("prescriptionType", "none");
            return;
        }

        List<Map<String, Object>> herbals = new ArrayList<>();
        for (Map<String, Object> item : toMapList(primary.get("items")))
        {
            Map<String, Object> herbal = new LinkedHashMap<>();
            herbal.put("name", item.get("name"));
            herbal.put("dosage", item.get("dosage"));
            herbal.put("unit", item.get("unit"));
            putIfPresent(herbal, "herbDictId", item.get("herbDictId"));
            putIfPresent(herbal, "inventoryId", item.get("inventoryId"));
            putIfPresent(herbal, "convertedQty", item.get("convertedQty"));
            putIfPresent(herbal, "convertedUnit", item.get("convertedUnit"));
            putIfPresent(herbal, "supplierId", item.get("supplierId"));
            putIfPresent(herbal, "supplierName", item.get("supplierName"));
            herbals.add(herbal);
        }
        payload.put("herbals", herbals);
        payload.put("formulaName", getString(primary, "formulaName", ""));
        payload.put("prescriptionType", getString(primary, "prescriptionType", "none"));
    }

    private boolean hasInventoryRelevantPrescriptionChange(JSONObject existingPayload, JSONObject nextPayload)
    {
        return !buildPrescriptionInventorySignature(existingPayload).equals(buildPrescriptionInventorySignature(nextPayload));
    }

    private void ensureDispensedPrescriptionsUnchanged(JSONObject stored, JSONObject incoming)
    {
        List<Map<String, Object>> previous = toMapList(stored.get("prescriptions"));
        List<Map<String, Object>> next = toMapList(incoming.get("prescriptions"));
        for (int i = 0; i < previous.size(); i++)
        {
            Map<String, Object> prescription = previous.get(i);
            if (isPrescriptionDeleted(prescription)
                    || !"dispensed".equals(resolvePrescriptionStatus(prescription, null))) continue;
            int nextIndex = findPrescriptionIndex(next, getString(prescription, "id", ""));
            if (nextIndex < 0 || !buildPrescriptionInventoryKey(stored, prescription, i)
                    .equals(buildPrescriptionInventoryKey(incoming, next.get(nextIndex), nextIndex)))
            {
                throw new ServiceException("已发处方请先回退后再修改或删除");
            }
        }
    }

    private String buildPrescriptionInventorySignature(JSONObject payload)
    {
        List<String> entries = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> prescription : toMapList(payload.get("prescriptions")))
        {
            entries.add(buildPrescriptionInventoryKey(payload, prescription, index));
            index++;
        }
        java.util.Collections.sort(entries);
        return JSON.toJSONString(entries);
    }

    private void inheritPrescriptionInventoryState(JSONObject incoming, JSONObject stored)
    {
        List<Map<String, Object>> previous = toMapList(stored.get("prescriptions"));
        List<Map<String, Object>> next = toMapList(incoming.get("prescriptions"));
        for (Map<String, Object> prescription : next)
        {
            int index = findPrescriptionIndex(previous, getString(prescription, "id", ""));
            Map<String, Object> authoritative = index < 0 ? java.util.Collections.emptyMap() : previous.get(index);
            for (String key : java.util.Arrays.asList("inventoryReservation", "inventorySyncedAt",
                    "inventoryReleasedAt", "inventoryActionPending", "deletedAt", "deletedBy"))
            {
                prescription.remove(key);
                if (authoritative.containsKey(key)) prescription.put(key, authoritative.get(key));
            }
            if (index >= 0)
            {
                for (String key : java.util.Arrays.asList("rxStatus", "dispensingCompleted", "dispensingCompletedAt", "dispensedBy"))
                {
                    prescription.remove(key);
                    if (authoritative.containsKey(key)) prescription.put(key, authoritative.get(key));
                }
            }
        }
        incoming.put("prescriptions", next);
    }

    private void savePrescriptionDraftContext(TcmConsultation existing, JSONObject payload,
            Map<String, Object> body)
    {
        if (!(body.get("consultation") instanceof Map) || isConsultationLocked(existing)) return;
        Map<?, ?> draft = (Map<?, ?>) body.get("consultation");
        // Save clinical notes in the same transaction as the stock reservation.
        // Reservation, payment and lifecycle fields remain server-owned.
        for (String key : java.util.Arrays.asList("appointmentId", "chiefComplaint", "chiefComplaintDuration",
                "chiefComplaintDescription", "progressOfDisease", "summary", "diff", "differentiation",
                "acupuncture", "prognosis", "feedback", "previousPrognosisReview", "servicePriceList", "services",
                "consultationFee", "discountType", "discountValue", "taxable", "includeRxAmount", "add3rdParty",
                "currency", "comments", "tongueImage", "documents", "historyAndMedication",
                "historyAndMedicationSnapshot", "historyAndMedicationSourceConsultId", "historyAndMedicationSourceConsultDate"))
        {
            if (draft.containsKey(key)) payload.put(key, draft.get(key));
        }
        if (draft.get("date") != null) existing.setConsultDate(PayloadUtils.toConsultation(java.util.Collections.singletonMap("date", draft.get("date"))).getConsultDate());
        if (draft.get("practitionerId") != null) existing.setPractitionerId(String.valueOf(draft.get("practitionerId")));
        if (draft.get("branchId") != null) existing.setBranchId(String.valueOf(draft.get("branchId")));
    }

    private String buildPrescriptionInventoryKey(JSONObject payload, Map<String, Object> prescription, int index)
    {
        String prescriptionId = getString(prescription, "id", "rx-" + index);
        String prescriptionType = getString(prescription, "prescriptionType", payload.getString("prescriptionType"));
        if (prescriptionType == null || prescriptionType.isEmpty())
        {
            prescriptionType = "raw_herbs";
        }
        boolean deleted = isPrescriptionDeleted(prescription);
        List<String> items = new ArrayList<>();
        if (!deleted && !"none".equals(prescriptionType))
        {
            for (Map<String, Object> item : buildReservationSnapshot(prescription))
            {
                items.add(buildReservationSnapshotKey(item));
            }
            java.util.Collections.sort(items);
        }
        return prescriptionId + "::" + deleted + "::" + prescriptionType + "::" + JSON.toJSONString(items);
    }

    private String buildReservationSnapshotKey(Map<String, Object> item)
    {
        String inventoryId = safeStringValue(item.get("inventoryId"));
        // An explicit stock ID is authoritative; refreshed display names or
        // supplier labels must not turn a notes-only save into a stock movement.
        String identity = StringUtils.isNotBlank(inventoryId)
                ? "id::" + inventoryId
                : "name::" + safeStringValue(item.get("name")) + "::" + safeStringValue(item.get("supplierId"));
        return identity + "::" + toBigDecimal(item.get("quantity")).stripTrailingZeros().toPlainString();
    }

    private void restoreReservationsFromPayload(JSONObject payload)
    {
        for (Map<String, Object> prescription : toMapList(payload.get("prescriptions")))
        {
            restoreReservationIfNeeded(prescription);
        }
    }

    private void releaseLifecycleReservations(JSONObject payload, String consultationStatus)
    {
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        boolean changed = false;
        for (Map<String, Object> prescription : prescriptions)
        {
            if (isDeletedPrescriptionHoldingInventory(prescription))
            {
                restoreReservationIfNeeded(prescription);
                prescription.put("inventoryReservation", new ArrayList<>());
                prescription.put("inventoryActionPending", false);
                prescription.put("inventoryReleasedAt", nowString());
                changed = true;
                continue;
            }
            if (!shouldSyncLifecycleReservation(prescription, consultationStatus))
            {
                if (prescription != null && !isPrescriptionDeleted(prescription) && !usesClinicInventory(prescription))
                {
                    restoreReservationIfNeeded(prescription);
                    prescription.put("inventoryReservation", new ArrayList<>());
                    prescription.put("inventorySyncedAt", null);
                    changed = true;
                }
                continue;
            }
            restoreReservationIfNeeded(prescription);
            prescription.put("inventoryReservation", new ArrayList<>());
            prescription.put("inventorySyncedAt", null);
            changed = true;
        }
        if (changed)
        {
            payload.put("prescriptions", prescriptions);
            syncPrimaryPrescriptionFields(payload);
            normalizePaymentState(payload);
        }
    }

    private void rebuildLifecycleReservations(JSONObject payload, String consultationStatus)
    {
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        String syncedAt = nowString();
        boolean changed = false;
        for (Map<String, Object> prescription : prescriptions)
        {
            if (isPrescriptionDeleted(prescription))
            {
                if (!isDeletedPrescriptionHoldingInventory(prescription))
                {
                    prescription.put("inventoryReservation", new ArrayList<>());
                    prescription.put("inventorySyncedAt", null);
                    changed = true;
                }
                continue;
            }
            if (!usesClinicInventory(prescription))
            {
                restoreReservationIfNeeded(prescription);
                prescription.put("inventoryReservation", new ArrayList<>());
                prescription.put("inventorySyncedAt", null);
                changed = true;
                continue;
            }
            if (!shouldSyncLifecycleReservation(prescription, consultationStatus))
            {
                continue;
            }
            // Older deleted consultations may still hold dispensed stock.
            if (!toMapList(prescription.get("inventoryReservation")).isEmpty()) continue;
            List<Map<String, Object>> reservation = reservePrescription(prescription);
            prescription.put("inventoryReservation", reservation);
            prescription.put("inventorySyncedAt", syncedAt);
            changed = true;
        }
        if (changed)
        {
            payload.put("prescriptions", prescriptions);
            syncPrimaryPrescriptionFields(payload);
            normalizePaymentState(payload);
        }
    }

    private void rebuildReservationsForPayload(JSONObject payload)
    {
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        String syncedAt = nowString();
        for (Map<String, Object> prescription : prescriptions)
        {
            if (isPrescriptionDeleted(prescription))
            {
                prescription.put("inventoryReservation", new ArrayList<>());
                prescription.put("inventoryActionPending", false);
                continue;
            }
            if (!usesClinicInventory(prescription))
            {
                prescription.put("inventoryReservation", new ArrayList<>());
                prescription.put("inventorySyncedAt", null);
                continue;
            }
            List<Map<String, Object>> reservation = reservePrescription(prescription);
            prescription.put("inventoryReservation", reservation);
            prescription.put("inventorySyncedAt", syncedAt);
        }
        payload.put("prescriptions", prescriptions);
        syncPrimaryPrescriptionFields(payload);
        normalizePaymentState(payload);
    }

    private int findPrescriptionIndex(List<Map<String, Object>> prescriptions, String prescriptionId)
    {
        for (int i = 0; i < prescriptions.size(); i++)
        {
            Map<String, Object> prescription = prescriptions.get(i);
            if (prescriptionId.equals(getString(prescription, "id", null)))
            {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> findPrescriptionOrThrow(List<Map<String, Object>> prescriptions, String prescriptionId)
    {
        int index = findPrescriptionIndex(prescriptions, prescriptionId);
        if (index < 0)
        {
            throw new ServiceException("处方不存在");
        }
        return prescriptions.get(index);
    }

    private void ensurePrescriptionCanEdit(Map<String, Object> prescription)
    {
        if (prescription == null)
        {
            return;
        }
        if (isPrescriptionDeleted(prescription)) throw new ServiceException("处方已删除，请先恢复后再修改");
        if ("dispensed".equals(resolvePrescriptionStatus(prescription, null)))
        {
            throw new ServiceException("已发处方请先回退后再修改");
        }
    }

    private void ensureReservationExists(Map<String, Object> prescription)
    {
        if (!usesClinicInventory(prescription))
        {
            restoreReservationIfNeeded(prescription);
            prescription.put("inventoryReservation", new ArrayList<>());
            prescription.put("inventorySyncedAt", null);
            return;
        }
        List<Map<String, Object>> reservation = toMapList(prescription.get("inventoryReservation"));
        if (!reservation.isEmpty())
        {
            return;
        }
        reservation = reservePrescription(prescription);
        prescription.put("inventoryReservation", reservation);
        prescription.put("inventorySyncedAt", nowString());
    }

    private List<Map<String, Object>> reservePrescription(Map<String, Object> prescription)
    {
        if (!usesClinicInventory(prescription))
        {
            return new ArrayList<>();
        }
        String prescriptionType = getString(prescription, "prescriptionType", "raw_herbs");
        List<Map<String, Object>> reservationItems = buildReservationSnapshot(prescription);
        if (reservationItems.isEmpty() || "none".equals(prescriptionType))
        {
            return new ArrayList<>();
        }
        for (Map<String, Object> item : reservationItems)
        {
            item.put("prescriptionId", prescription.get("id"));
        }
        Map<String, Object> result = inventoryService.deductFromPrescription(reservationItems, prescriptionType);
        if (!Boolean.TRUE.equals(result.get("success")))
        {
            List<?> errors = (List<?>) result.get("errors");
            String message = (errors != null && !errors.isEmpty())
                    ? String.join("；", stringify(errors))
                    : "库存扣减失败";
            throw new ServiceException(message);
        }
        List<Map<String, Object>> deducted = toMapList(result.get("deducted"));
        List<Map<String, Object>> reservation = new ArrayList<>();
        for (Map<String, Object> item : deducted)
        {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("inventoryId", item.get("inventoryId"));
            record.put("name", item.get("name"));
            record.put("reservedQty", item.get("quantity"));
            putIfPresent(record, "supplierId", item.get("supplierId"));
            putIfPresent(record, "supplier", item.get("supplier"));
            reservation.add(record);
        }
        return reservation;
    }

    private boolean canReuseReservation(Map<String, Object> current, Map<String, Object> nextPrescription)
    {
        if (current == null)
        {
            return false;
        }
        List<Map<String, Object>> currentReservation = toMapList(current.get("inventoryReservation"));
        if (currentReservation.isEmpty())
        {
            return false;
        }
        String currentType = getString(current, "prescriptionType", "raw_herbs");
        String nextType = getString(nextPrescription, "prescriptionType", "raw_herbs");
        if (!usesClinicInventory(current) || !usesClinicInventory(nextPrescription))
        {
            return false;
        }
        if (!currentType.equals(nextType))
        {
            return false;
        }
        return buildReservationSnapshot(current).equals(buildReservationSnapshot(nextPrescription));
    }

    private boolean shouldSyncLifecycleReservation(Map<String, Object> prescription, String consultationStatus)
    {
        if (prescription == null || isPrescriptionDeleted(prescription))
        {
            return false;
        }
        if (!usesClinicInventory(prescription))
        {
            return false;
        }
        return true;
    }

    private boolean usesClinicInventory(Map<String, Object> prescription)
    {
        if (prescription == null || isPrescriptionDeleted(prescription))
        {
            return false;
        }
        String prescriptionType = getString(prescription, "prescriptionType", "raw_herbs");
        if ("none".equals(prescriptionType))
        {
            return false;
        }
        String whereToGet = getString(prescription, "whereToGet", "");
        String normalized = whereToGet == null ? "" : whereToGet.toLowerCase();
        return !normalized.contains("external") && !normalized.contains("外部");
    }

    private void restoreReservationIfNeeded(Map<String, Object> prescription)
    {
        if (prescription == null)
        {
            return;
        }
        List<Map<String, Object>> reservation = toMapList(prescription.get("inventoryReservation"));
        if (reservation.isEmpty())
        {
            return;
        }
        List<Map<String, Object>> restoreItems = new ArrayList<>();
        for (Map<String, Object> item : reservation)
        {
            Map<String, Object> restoreItem = new LinkedHashMap<>();
            restoreItem.put("inventoryId", item.get("inventoryId"));
            restoreItem.put("name", item.get("name"));
            restoreItem.put("quantity", item.get("reservedQty"));
            restoreItem.put("prescriptionId", prescription.get("id"));
            putIfPresent(restoreItem, "supplierId", item.get("supplierId"));
            restoreItems.add(restoreItem);
        }
        Map<String, Object> result = inventoryService.restoreFromPrescription(
                restoreItems,
                getString(prescription, "prescriptionType", "raw_herbs"));
        if (!Boolean.TRUE.equals(result.get("success")))
        {
            throw new ServiceException("库存恢复失败");
        }
    }

    private List<Map<String, Object>> buildReservationSnapshot(Map<String, Object> prescription)
    {
        if (!usesClinicInventory(prescription))
        {
            return new ArrayList<>();
        }
        List<Map<String, Object>> items = toMapList(prescription.get("items"));
        if (items.isEmpty())
        {
            return new ArrayList<>();
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        BigDecimal quantity = toBigDecimal(prescription.get("quantity"));
        if (quantity.compareTo(BigDecimal.ZERO) <= 0)
        {
            quantity = BigDecimal.ONE;
        }

        for (Map<String, Object> item : items)
        {
            String name = getString(item, "name", null);
            if (name == null || name.isEmpty())
            {
                continue;
            }
            String inventoryId = getString(item, "inventoryId", null);
            String supplierId = getString(item, "supplierId", null);
            String key = safeString(inventoryId) + "::" + name + "::" + safeString(supplierId);
            Map<String, Object> mergedItem = merged.get(key);
            if (mergedItem == null)
            {
                mergedItem = new LinkedHashMap<>();
                mergedItem.put("name", name);
                putIfPresent(mergedItem, "inventoryId", inventoryId);
                putIfPresent(mergedItem, "supplierId", supplierId);
                mergedItem.put("quantity", BigDecimal.ZERO);
                merged.put(key, mergedItem);
            }

            BigDecimal requestedQty = toBigDecimal(item.get("convertedQty"));
            if (requestedQty.compareTo(BigDecimal.ZERO) <= 0)
            {
                requestedQty = toBigDecimal(item.get("dosage")).multiply(quantity);
            }
            mergedItem.put("quantity", toBigDecimal(mergedItem.get("quantity")).add(requestedQty));
        }
        return new ArrayList<>(merged.values());
    }

    private boolean hasAnyDispensedPrescription(List<Map<String, Object>> prescriptions, String consultationStatus)
    {
        for (Map<String, Object> prescription : prescriptions)
        {
            if (isPrescriptionDeleted(prescription))
            {
                continue;
            }
            if ("dispensed".equals(resolvePrescriptionStatus(prescription, consultationStatus)))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isPrescriptionDeleted(Map<String, Object> prescription)
    {
        String deletedAt = getString(prescription, "deletedAt", null);
        return deletedAt != null && !deletedAt.isEmpty();
    }

    private boolean isDeletedPrescriptionHoldingInventory(Map<String, Object> prescription)
    {
        return prescription != null
                && isPrescriptionDeleted(prescription)
                && toBoolean(prescription.get("inventoryActionPending"))
                && !toMapList(prescription.get("inventoryReservation")).isEmpty();
    }

    private boolean isConsultationLocked(TcmConsultation consultation)
    {
        if (consultation == null)
        {
            return false;
        }
        return StringUtils.isNotEmpty(consultation.getLockedAt())
                || "completed".equals(consultation.getStatus())
                || "paid".equals(consultation.getStatus());
    }

    private void normalizePaymentState(JSONObject payload)
    {
        BigDecimal totalAmount = toBigDecimal(payload.get("totalAmount"));
        BigDecimal paidAmount = sumPaymentRecords(payload);
        BigDecimal outstanding = totalAmount.subtract(paidAmount);
        if (outstanding.compareTo(BigDecimal.ZERO) < 0)
        {
            outstanding = BigDecimal.ZERO;
        }
        payload.put("paidAmount", paidAmount);
        payload.put("outstandingAmount", outstanding);
        if (paidAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            payload.put("paymentStatus", "unpaid");
        }
        else if (outstanding.compareTo(BigDecimal.ZERO) > 0)
        {
            payload.put("paymentStatus", "partial");
        }
        else
        {
            payload.put("paymentStatus", "paid");
        }
    }

    private BigDecimal sumPaymentRecords(JSONObject payload)
    {
        JSONArray paymentRecords = payload.getJSONArray("paymentRecords");
        if (paymentRecords == null || paymentRecords.isEmpty())
        {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Object record : paymentRecords)
        {
            if (record instanceof Map<?, ?>)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> paymentRecord = (Map<String, Object>) record;
                sum = sum.add(toBigDecimal(paymentRecord.get("amount")));
            }
            else if (record instanceof JSONObject)
            {
                sum = sum.add(toBigDecimal(((JSONObject) record).get("amount")));
            }
        }
        return sum;
    }

    private String resolvePersistedStatus(String existingStatus, String incomingStatus)
    {
        String normalizedExisting = StringUtils.isNotEmpty(existingStatus) ? existingStatus : "draft";
        String normalizedIncoming = StringUtils.isNotEmpty(incomingStatus) ? incomingStatus : normalizedExisting;
        if (consultationStatusRank(normalizedIncoming) < consultationStatusRank(normalizedExisting))
        {
            return normalizedExisting;
        }
        return normalizedIncoming;
    }

    private int consultationStatusRank(String status)
    {
        if ("paid".equals(status))
        {
            return 3;
        }
        if ("completed".equals(status))
        {
            return 2;
        }
        if ("draft".equals(status))
        {
            return 1;
        }
        return 0;
    }

    private JSONObject parsePayload(String payloadStr)
    {
        if (payloadStr != null && !payloadStr.isEmpty())
        {
            try
            {
                return JSON.parseObject(payloadStr);
            }
            catch (Exception e)
            {
                return new JSONObject();
            }
        }
        return new JSONObject();
    }

    private HistorySourceContext normalizeHistorySnapshot(TcmConsultation consultation, TcmConsultation existing)
    {
        JSONObject payload = parsePayload(consultation.getPayload());
        TcmConsultation sourceConsultation = resolveSourceConsultation(
                listPatientConsultations(consultation.getPatientId()), consultation);
        boolean isSourceConsultation = sourceConsultation == null
                || consultation.getId().equals(sourceConsultation.getId());
        String snapshot;
        String sourceConsultationId;
        String sourceConsultationDate;

        if (isSourceConsultation)
        {
            snapshot = firstNonBlank(
                    payload.getString("historyAndMedicationSnapshot"),
                    payload.getString("historyAndMedication"),
                    existing != null ? extractHistorySnapshot(parsePayload(existing.getPayload())) : null);
            sourceConsultationId = consultation.getId();
            sourceConsultationDate = consultation.getConsultDate();
        }
        else
        {
            JSONObject sourcePayload = parsePayload(sourceConsultation.getPayload());
            snapshot = firstNonBlank(
                    extractHistorySnapshot(sourcePayload),
                    payload.getString("historyAndMedicationSnapshot"),
                    payload.getString("historyAndMedication"));
            sourceConsultationId = sourceConsultation.getId();
            sourceConsultationDate = sourceConsultation.getConsultDate();
        }

        writeHistorySnapshot(payload, snapshot, sourceConsultationId, sourceConsultationDate);
        consultation.setPayload(payload.toJSONString());
        return new HistorySourceContext(sourceConsultationId, sourceConsultationDate, snapshot, isSourceConsultation);
    }

    private void propagateHistorySnapshot(String patientId, HistorySourceContext historyContext)
    {
        if (patientId == null || patientId.isEmpty())
        {
            return;
        }
        for (TcmConsultation consultation : listPatientConsultations(patientId))
        {
            JSONObject payload = parsePayload(consultation.getPayload());
            writeHistorySnapshot(
                    payload,
                    historyContext.getSnapshot(),
                    historyContext.getSourceConsultationId(),
                    historyContext.getSourceConsultationDate());
            consultation.setPayload(payload.toJSONString());
            consultationMapper.updateTcmConsultation(consultation);
        }
    }

    private List<TcmConsultation> listPatientConsultations(String patientId)
    {
        if (patientId == null || patientId.isEmpty())
        {
            return new ArrayList<>();
        }
        TcmConsultation query = new TcmConsultation();
        query.setPatientId(patientId);
        return consultationMapper.selectTcmConsultationList(query);
    }

    private TcmConsultation resolveSourceConsultation(List<TcmConsultation> patientConsultations, TcmConsultation current)
    {
        List<TcmConsultation> candidates = new ArrayList<>();
        boolean replaced = false;
        for (TcmConsultation consultation : patientConsultations)
        {
            if (consultation == null)
            {
                continue;
            }
            if (current.getId() != null && current.getId().equals(consultation.getId()))
            {
                candidates.add(current);
                replaced = true;
            }
            else
            {
                candidates.add(consultation);
            }
        }
        if (!replaced)
        {
            candidates.add(current);
        }
        candidates.sort(this::compareConsultationOrder);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private int compareConsultationOrder(TcmConsultation left, TcmConsultation right)
    {
        int dateCompare = safeString(left.getConsultDate()).compareTo(safeString(right.getConsultDate()));
        if (dateCompare != 0)
        {
            return dateCompare;
        }
        long leftCreateTime = left.getCreateTime() != null ? left.getCreateTime().getTime() : 0L;
        long rightCreateTime = right.getCreateTime() != null ? right.getCreateTime().getTime() : 0L;
        int createTimeCompare = Long.compare(leftCreateTime, rightCreateTime);
        if (createTimeCompare != 0)
        {
            return createTimeCompare;
        }
        return safeString(left.getId()).compareTo(safeString(right.getId()));
    }

    private void writeHistorySnapshot(
            JSONObject payload,
            String snapshot,
            String sourceConsultationId,
            String sourceConsultationDate)
    {
        if (snapshot == null || snapshot.trim().isEmpty())
        {
            payload.remove("historyAndMedication");
            payload.remove("historyAndMedicationSnapshot");
        }
        else
        {
            payload.put("historyAndMedication", snapshot);
            payload.put("historyAndMedicationSnapshot", snapshot);
        }

        if (sourceConsultationId == null || sourceConsultationId.isEmpty())
        {
            payload.remove("historyAndMedicationSourceConsultId");
        }
        else
        {
            payload.put("historyAndMedicationSourceConsultId", sourceConsultationId);
        }

        if (sourceConsultationDate == null || sourceConsultationDate.isEmpty())
        {
            payload.remove("historyAndMedicationSourceConsultDate");
        }
        else
        {
            payload.put("historyAndMedicationSourceConsultDate", sourceConsultationDate);
        }
    }

    private String extractHistorySnapshot(JSONObject payload)
    {
        if (payload == null)
        {
            return null;
        }
        return firstNonBlank(
                payload.getString("historyAndMedicationSnapshot"),
                payload.getString("historyAndMedication"));
    }

    private String firstNonBlank(String... values)
    {
        if (values == null)
        {
            return null;
        }
        for (String value : values)
        {
            if (value != null && !value.trim().isEmpty())
            {
                return value;
            }
        }
        return null;
    }

    private String safeStringValue(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private String safeString(String value)
    {
        return value != null ? value : "";
    }

    private void insertConsultationMod(
            TcmConsultation consultation,
            String actorId,
            String modType,
            String action,
            String changes,
            String modDate)
    {
        TcmConsultationMod mod = new TcmConsultationMod();
        mod.setConsultationId(consultation.getConsultationId());
        mod.setModDate(modDate);
        mod.setModType(modType);
        mod.setAction(action);
        mod.setUserId(actorId);
        mod.setVersion(consultation.getVersion() != null ? consultation.getVersion() : 1);
        mod.setChanges(changes);
        modMapper.insertTcmConsultationMod(mod);
    }

    private void appendPayloadModification(
            JSONObject payload,
            String type,
            String action,
            String actorId,
            String changes,
            String date)
    {
        JSONArray modifications = payload.getJSONArray("modifications");
        if (modifications == null)
        {
            modifications = new JSONArray();
        }
        JSONObject modification = new JSONObject();
        modification.put("date", date);
        modification.put("type", type);
        modification.put("action", action);
        modification.put("userId", actorId);
        if (changes != null && !changes.isEmpty())
        {
            modification.put("changes", changes);
        }
        modifications.add(modification);
        payload.put("modifications", modifications);
    }

    private String buildPaymentSummary(JSONObject payload)
    {
        List<String> parts = new ArrayList<>();
        String method = payload.getString("paymentMethod");
        if (method != null && !method.isEmpty())
        {
            parts.add("支付方式: " + method);
        }
        String reference = payload.getString("paymentReference");
        if (reference != null && !reference.isEmpty())
        {
            parts.add("流水号: " + reference);
        }
        Object totalAmount = payload.get("totalAmount");
        if (totalAmount != null)
        {
            parts.add("收款金额: " + totalAmount);
        }
        String invoiceUrl = payload.getString("invoicePdfUrl");
        if (invoiceUrl != null && !invoiceUrl.isEmpty())
        {
            parts.add("已生成发票");
        }
        return String.join("；", parts);
    }

    private String buildDispenseSummary(List<PrescriptionGroup> groups)
    {
        if (groups.isEmpty())
        {
            return "无库存扣减";
        }
        List<String> parts = new ArrayList<>();
        for (PrescriptionGroup group : groups)
        {
            if (group.getHerbals().isEmpty() || "none".equals(group.getPrescriptionType()))
            {
                continue;
            }
            parts.add(group.getPrescriptionType() + " " + group.getHerbals().size() + "味");
        }
        return parts.isEmpty() ? "无库存扣减" : String.join("；", parts);
    }

    private void deductInventoryOrThrow(List<PrescriptionGroup> groups)
    {
        for (PrescriptionGroup group : groups)
        {
            if (group.getHerbals().isEmpty() || "none".equals(group.getPrescriptionType()))
            {
                continue;
            }
            Map<String, Object> result = inventoryService.deductFromPrescription(
                    group.getHerbals(), group.getPrescriptionType());
            if (!Boolean.TRUE.equals(result.get("success")))
            {
                List<?> errors = (List<?>) result.get("errors");
                String message = (errors != null && !errors.isEmpty())
                        ? String.join("；", stringify(errors))
                        : "库存扣减失败";
                throw new ServiceException(message);
            }
        }
    }

    private void resetPrescriptionDispenseFlags(JSONObject payload)
    {
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        if (prescriptions.isEmpty())
        {
            return;
        }
        for (Map<String, Object> prescription : prescriptions)
        {
            prescription.put("dispensingCompleted", false);
        }
        payload.put("prescriptions", prescriptions);
    }

    private boolean hasInventoryPreDeducted(JSONObject payload)
    {
        return payload != null
                && (payload.getBooleanValue("inventoryDeductedAtPayment")
                || StringUtils.isNotEmpty(payload.getString("inventoryDeductedAt")));
    }

    private List<PrescriptionGroup> buildPrescriptionGroups(JSONObject payload)
    {
        Map<String, Map<String, Map<String, Object>>> groups = new LinkedHashMap<>();
        List<Map<String, Object>> prescriptions = toMapList(payload.get("prescriptions"));
        if (!prescriptions.isEmpty())
        {
            for (Map<String, Object> prescription : prescriptions)
            {
                String prescriptionType = getString(prescription, "prescriptionType",
                        payload.getString("prescriptionType"));
                if (prescriptionType == null || prescriptionType.isEmpty())
                {
                    prescriptionType = "raw_herbs";
                }
                if ("none".equals(prescriptionType))
                {
                    continue;
                }
                BigDecimal quantity = toBigDecimal(prescription.get("quantity"));
                if (quantity.compareTo(BigDecimal.ZERO) <= 0)
                {
                    quantity = BigDecimal.ONE;
                }
                List<Map<String, Object>> items = toMapList(prescription.get("items"));
                mergePrescriptionItems(groups, prescriptionType, items, quantity);
            }
        }

        if (groups.isEmpty())
        {
            String prescriptionType = payload.getString("prescriptionType");
            if (prescriptionType == null || prescriptionType.isEmpty())
            {
                prescriptionType = "raw_herbs";
            }
            List<Map<String, Object>> herbals = toMapList(payload.get("herbals"));
            if (!herbals.isEmpty() && !"none".equals(prescriptionType))
            {
                List<PrescriptionGroup> fallback = new ArrayList<>();
                fallback.add(new PrescriptionGroup(prescriptionType, herbals));
                return fallback;
            }
        }

        List<PrescriptionGroup> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : groups.entrySet())
        {
            PrescriptionGroup group = new PrescriptionGroup(entry.getKey(), new ArrayList<>(entry.getValue().values()));
            result.add(group);
        }
        return result;
    }

    private void mergePrescriptionItems(
            Map<String, Map<String, Map<String, Object>>> groups,
            String prescriptionType,
            List<Map<String, Object>> items,
            BigDecimal quantity)
    {
        if (items == null || items.isEmpty())
        {
            return;
        }
        Map<String, Map<String, Object>> currentGroup = groups.computeIfAbsent(
                prescriptionType, key -> new LinkedHashMap<>());
        for (Map<String, Object> item : items)
        {
            String name = getString(item, "name", null);
            if (name == null || name.isEmpty())
            {
                continue;
            }
            String unit = getString(item, "unit", null);
            String supplierId = getString(item, "supplierId", null);
            String groupKey = name + "::" + (unit != null ? unit : "") + "::" + (supplierId != null ? supplierId : "");
            Map<String, Object> merged = currentGroup.get(groupKey);
            if (merged == null)
            {
                merged = new LinkedHashMap<>();
                merged.put("name", name);
                if (unit != null && !unit.isEmpty())
                {
                    merged.put("unit", unit);
                }
                if (supplierId != null && !supplierId.isEmpty())
                {
                    merged.put("supplierId", supplierId);
                }
                merged.put("dosage", BigDecimal.ZERO);
                currentGroup.put(groupKey, merged);
            }
            BigDecimal dosage = toBigDecimal(item.get("dosage")).multiply(quantity);
            merged.put("dosage", toBigDecimal(merged.get("dosage")).add(dosage));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object value)
    {
        if (value == null)
        {
            return new ArrayList<>();
        }
        if (value instanceof List<?>)
        {
            List<?> list = (List<?>) value;
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object entry : list)
            {
                if (entry instanceof Map<?, ?>)
                {
                    result.add(new LinkedHashMap<>((Map<String, Object>) entry));
                }
                else if (entry != null)
                {
                    try
                    {
                        result.add(JSON.parseObject(JSON.toJSONString(entry), Map.class));
                    }
                    catch (Exception e)
                    {
                        // ignore invalid entry
                    }
                }
            }
            return result;
        }
        if (value instanceof String && !((String) value).isEmpty())
        {
            try
            {
                return toMapList(JSON.parseArray((String) value));
            }
            catch (Exception e)
            {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    private void putIfPresent(JSONObject payload, String key, Object value)
    {
        if (value == null)
        {
            return;
        }
        String strValue = String.valueOf(value);
        if (!strValue.isEmpty() && !"null".equalsIgnoreCase(strValue))
        {
            payload.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value)
    {
        if (payload == null || value == null)
        {
            return;
        }
        String strValue = String.valueOf(value);
        if (!strValue.isEmpty() && !"null".equalsIgnoreCase(strValue))
        {
            payload.put(key, value);
        }
    }

    private String getString(Map<String, Object> source, String key, String defaultValue)
    {
        if (source == null)
        {
            return defaultValue;
        }
        Object value = source.get(key);
        if (value == null)
        {
            return defaultValue;
        }
        String str = String.valueOf(value);
        return str.isEmpty() ? defaultValue : str;
    }

    private BigDecimal toBigDecimal(Object value)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        if (value instanceof Number)
        {
            return new BigDecimal(String.valueOf(value));
        }
        try
        {
            return new BigDecimal(String.valueOf(value));
        }
        catch (Exception e)
        {
            return BigDecimal.ZERO;
        }
    }

    private boolean toBoolean(Object value)
    {
        if (value == null)
        {
            return false;
        }
        if (value instanceof Boolean)
        {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private List<String> stringify(List<?> values)
    {
        List<String> result = new ArrayList<>();
        for (Object value : values)
        {
            if (value != null)
            {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    /**
     * 生成问诊编号
     * 格式: ORD-XXXXX-XXXXXX
     */
    private String generateConsultationId()
    {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 10; attempt++)
        {
            String part1 = String.format("%05d", random.nextInt(100000));
            String part2 = String.format("%06d", random.nextInt(1000000));
            String consultationId = "ORD-" + part1 + "-" + part2;
            // 检查是否已存在相同编号
            TcmConsultation query = new TcmConsultation();
            query.setConsultationId(consultationId);
            List<TcmConsultation> existing = consultationMapper.selectTcmConsultationList(query);
            if (existing == null || existing.isEmpty())
            {
                return consultationId;
            }
        }
        // 兜底：使用时间戳避免冲突
        return "ORD-" + System.currentTimeMillis();
    }

    private String nowString()
    {
        return new SimpleDateFormat(DATETIME_FORMAT).format(new Date());
    }

    private static class HistorySourceContext
    {
        private final String sourceConsultationId;
        private final String sourceConsultationDate;
        private final String snapshot;
        private final boolean sourceConsultation;

        HistorySourceContext(
                String sourceConsultationId,
                String sourceConsultationDate,
                String snapshot,
                boolean sourceConsultation)
        {
            this.sourceConsultationId = sourceConsultationId;
            this.sourceConsultationDate = sourceConsultationDate;
            this.snapshot = snapshot;
            this.sourceConsultation = sourceConsultation;
        }

        String getSourceConsultationId()
        {
            return sourceConsultationId;
        }

        String getSourceConsultationDate()
        {
            return sourceConsultationDate;
        }

        String getSnapshot()
        {
            return snapshot;
        }

        boolean isSourceConsultation()
        {
            return sourceConsultation;
        }
    }

    private static class PrescriptionGroup
    {
        private final String prescriptionType;
        private final List<Map<String, Object>> herbals;

        PrescriptionGroup(String prescriptionType, List<Map<String, Object>> herbals)
        {
            this.prescriptionType = prescriptionType;
            this.herbals = herbals;
        }

        public String getPrescriptionType()
        {
            return prescriptionType;
        }

        public List<Map<String, Object>> getHerbals()
        {
            return herbals;
        }
    }

    private static class FileRef
    {
        private final String path;
        private final String type;
        private final String name;

        FileRef(String path, String type, String name)
        {
            this.path = path;
            this.type = StringUtils.isNotEmpty(type) ? type : "document";
            this.name = StringUtils.isNotEmpty(name) ? name : "document";
        }
    }
}
