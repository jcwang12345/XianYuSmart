package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.OrderMatrixService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 新版订单与售后工作台；旧订单接口保持兼容。 */
@RestController
@RequestMapping("/api/order-matrix")
public class OrderMatrixController {

    private final OrderMatrixService service;

    public OrderMatrixController(OrderMatrixService service) {
        this.service = service;
    }

    @PostMapping("/orders/query")
    public ResultObject<Map<String, Object>> list(@RequestBody(required = false) OrderMatrixService.OrderFilter filter) {
        return ResultObject.success(service.list(filter));
    }

    @GetMapping("/orders/{orderRecordId}")
    public ResultObject<Map<String, Object>> detail(@PathVariable Long orderRecordId) {
        return ResultObject.success(service.detail(orderRecordId));
    }

    @GetMapping("/accounts/{accountId}/capabilities")
    public ResultObject<Map<String, Object>> capabilities(@PathVariable Long accountId) {
        return ResultObject.success(service.capabilities(accountId));
    }

    @PostMapping("/orders/{orderRecordId}/physical-shipment/preview")
    public ResultObject<Map<String, Object>> physicalShipmentPreview(
            @PathVariable Long orderRecordId, @RequestBody OrderMatrixService.ShipmentCommand command) {
        return ResultObject.success(service.physicalShipmentPreview(orderRecordId, command));
    }

    @PostMapping("/orders/{orderRecordId}/physical-shipment/record")
    public ResultObject<Map<String, Object>> recordPhysicalShipment(
            @PathVariable Long orderRecordId, @RequestBody OrderMatrixService.ShipmentCommand command) {
        return ResultObject.success(service.recordPhysicalShipment(orderRecordId, command));
    }

    @PostMapping("/refunds/{refundCaseId}/return-shipment/preview")
    public ResultObject<Map<String, Object>> returnShipmentPreview(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.ReturnShipmentCommand command) {
        return ResultObject.success(service.returnShipmentPreview(refundCaseId, command));
    }

    @PostMapping("/refunds/{refundCaseId}/return-shipment/record")
    public ResultObject<Map<String, Object>> recordReturnShipment(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.ReturnShipmentCommand command) {
        return ResultObject.success(service.recordReturnShipment(refundCaseId, command));
    }

    @PutMapping("/orders/{orderRecordId}/note")
    public ResultObject<Map<String, Object>> updateNote(
            @PathVariable Long orderRecordId, @RequestBody OrderMatrixService.OrderNoteCommand command) {
        return ResultObject.success(service.updateNote(orderRecordId, command));
    }

    @PostMapping("/refunds/{refundCaseId}/approve/preview")
    public ResultObject<Map<String, Object>> approvePreview(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.RefundActionCommand command) {
        return ResultObject.success(service.refundActionPreview(refundCaseId, action(command, "APPROVE")));
    }

    @PostMapping("/refunds/{refundCaseId}/approve/execute")
    public ResultObject<Map<String, Object>> approve(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.RefundActionCommand command) {
        return ResultObject.success(service.executeRefundAction(refundCaseId, action(command, "APPROVE")));
    }

    @PostMapping("/refunds/{refundCaseId}/reject/preview")
    public ResultObject<Map<String, Object>> rejectPreview(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.RefundActionCommand command) {
        return ResultObject.success(service.refundActionPreview(refundCaseId, action(command, "REJECT")));
    }

    @PostMapping("/refunds/{refundCaseId}/reject/execute")
    public ResultObject<Map<String, Object>> reject(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.RefundActionCommand command) {
        return ResultObject.success(service.executeRefundAction(refundCaseId, action(command, "REJECT")));
    }

    @PostMapping("/refunds/{refundCaseId}/note/preview")
    public ResultObject<Map<String, Object>> refundNotePreview(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.RefundActionCommand command) {
        return ResultObject.success(service.refundActionPreview(refundCaseId, action(command, "ADD_NOTE")));
    }

    @PostMapping("/refunds/{refundCaseId}/note/execute")
    public ResultObject<Map<String, Object>> refundNote(
            @PathVariable Long refundCaseId, @RequestBody OrderMatrixService.RefundActionCommand command) {
        return ResultObject.success(service.executeRefundAction(refundCaseId, action(command, "ADD_NOTE")));
    }

    private OrderMatrixService.RefundActionCommand action(OrderMatrixService.RefundActionCommand command,
                                                          String action) {
        return new OrderMatrixService.RefundActionCommand(command.requestId(), action, command.reason(),
                command.confirmationText());
    }
}
