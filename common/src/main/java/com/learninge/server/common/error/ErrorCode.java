package com.learninge.server.common.error;

import io.grpc.Status;

/**
 * Mã lỗi nghiệp vụ gửi trong trailer {@code error-code} (GRPC-API §Quy ước lỗi).
 *
 * <p>Tên hằng chính là chuỗi app nhận được, nên không đổi tên trong v1.
 */
public enum ErrorCode {
  VALIDATION_ERROR(Status.Code.INVALID_ARGUMENT, "Dữ liệu chưa hợp lệ"),
  BATCH_TOO_LARGE(Status.Code.INVALID_ARGUMENT, "Tối đa 500 bản ghi mỗi lần"),
  UNAUTHENTICATED(Status.Code.UNAUTHENTICATED, "Phiên đăng nhập đã hết hạn"),
  INVALID_CREDENTIALS(Status.Code.UNAUTHENTICATED, "Email hoặc mật khẩu không đúng"),
  INVALID_SECURITY_ANSWER(Status.Code.UNAUTHENTICATED, "Thông tin không khớp"),
  ACCOUNT_DELETED(Status.Code.PERMISSION_DENIED, "Tài khoản đã bị xóa"),
  NOT_FOUND(Status.Code.NOT_FOUND, "Không tìm thấy"),
  EMAIL_TAKEN(Status.Code.ALREADY_EXISTS, "Email đã được dùng"),
  NICKNAME_TAKEN(Status.Code.ALREADY_EXISTS, "Biệt danh đã được sử dụng"),
  PAYLOAD_TOO_LARGE(Status.Code.RESOURCE_EXHAUSTED, "Dữ liệu gửi lên quá lớn"),
  RATE_LIMITED(Status.Code.RESOURCE_EXHAUSTED, "Bạn thao tác quá nhanh"),
  ACCOUNT_LOCKED(Status.Code.RESOURCE_EXHAUSTED, "Bạn đã nhập sai quá nhiều lần"),
  RESET_LOCKED(Status.Code.RESOURCE_EXHAUSTED, "Bạn đã trả lời sai quá nhiều lần"),
  UPGRADE_REQUIRED(Status.Code.FAILED_PRECONDITION, "Vui lòng cập nhật ứng dụng"),
  SERVICE_UNAVAILABLE(Status.Code.UNAVAILABLE, "Tính năng tạm thời không dùng được"),
  INTERNAL_ERROR(Status.Code.INTERNAL, "Máy chủ gặp lỗi, vui lòng thử lại");

  private final Status.Code status;
  private final String defaultMessage;

  ErrorCode(Status.Code status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public Status.Code status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
