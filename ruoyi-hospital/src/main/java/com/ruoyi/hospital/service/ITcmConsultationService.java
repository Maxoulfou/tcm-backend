package com.ruoyi.hospital.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.hospital.domain.TcmConsultation;

/**
 * 中医问诊 Service接口
 *
 * @author ruoyi
 */
public interface ITcmConsultationService
{
    /**
     * 查询问诊列表
     */
    List<TcmConsultation> selectTcmConsultationList(TcmConsultation consultation);

    /**
     * 查询问诊详情
     */
    TcmConsultation selectTcmConsultationById(String id);

    /**
     * 新增问诊
     */
    int insertTcmConsultation(TcmConsultation consultation);

    /**
     * 修改问诊
     */
    int updateTcmConsultation(TcmConsultation consultation, String actorId);

    /**
     * 完成问诊
     */
    TcmConsultation completeConsultation(String id, String actorId);

    /**
     * 重新激活已完成问诊，进入下一版本编辑
     */
    TcmConsultation reactivateConsultation(String id, String actorId);

    /**
     * 标记已付款
     */
    TcmConsultation markPaid(String id, String actorId, Map<String, Object> paymentInfo);

    /**
     * Update invoice-only pricing fields without unlocking the consultation report.
     */
    TcmConsultation updateInvoicePricing(String id, String actorId, Map<String, Object> pricingInfo);

    /**
     * 同步单张处方并联动库存占用
     */
    TcmConsultation syncPrescription(String id, Map<String, Object> prescriptionData, String actorId);

    /**
     * 完成单张处方，进入待发状态
     */
    TcmConsultation completePrescription(String id, String prescriptionId, Map<String, Object> payload, String actorId);

    /**
     * 发药单张处方
     */
    TcmConsultation dispensePrescription(String id, String prescriptionId, String actorId);

    /**
     * 管理员回退已发处方
     */
    TcmConsultation reopenPrescription(String id, String prescriptionId, String actorId);

    /**
     * 删除单张处方到回收站
     */
    TcmConsultation deletePrescription(String id, String prescriptionId, Map<String, Object> payload, String actorId);

    /**
     * 查询已删除处方
     */
    List<Map<String, Object>> listDeletedPrescriptions();

    /**
     * 恢复已删除处方
     */
    TcmConsultation restoreDeletedPrescription(String id, String prescriptionId, String actorId);

    /**
     * 永久删除已删除处方
     */
    TcmConsultation permanentlyDeletePrescription(String id, String prescriptionId, boolean restoreInventory, String actorId);

    /**
     * 记录一次付款
     */
    TcmConsultation recordPayment(String id, String actorId, Map<String, Object> paymentInfo);

    /**
     * 按第三方支付结果幂等记录付款
     */
    ProviderPaymentResult recordProviderPayment(String id, String actorId, Map<String, Object> paymentInfo);

    /**
     * 标记配药完成
     */
    TcmConsultation markDispensingComplete(String id, String actorId);

    /**
     * 标记配药完成（可在库存已预扣时跳过重复扣减）
     *
     * @param skipDeduct 是否尝试跳过库存扣减
     */
    TcmConsultation markDispensingComplete(String id, String actorId, boolean skipDeduct);

    /**
     * 软删除问诊
     */
    TcmConsultation softDeleteTcmConsultation(String id);

    /**
     * 恢复已软删除的问诊
     */
    TcmConsultation restoreTcmConsultation(String id);

    /**
     * 硬删除问诊
     */
    int hardDeleteTcmConsultation(String id);
}
