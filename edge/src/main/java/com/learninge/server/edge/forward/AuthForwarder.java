package com.learninge.server.edge.forward;

import com.learninge.proto.app.v1.AuthServiceGrpc;
import com.learninge.proto.app.v1.AuthSession;
import com.learninge.proto.app.v1.ChangePasswordRequest;
import com.learninge.proto.app.v1.ChangePasswordResponse;
import com.learninge.proto.app.v1.DeleteAccountRequest;
import com.learninge.proto.app.v1.DeleteAccountResponse;
import com.learninge.proto.app.v1.GetMeRequest;
import com.learninge.proto.app.v1.GetMeResponse;
import com.learninge.proto.app.v1.GetResetQuestionRequest;
import com.learninge.proto.app.v1.GetResetQuestionResponse;
import com.learninge.proto.app.v1.LoginRequest;
import com.learninge.proto.app.v1.LogoutRequest;
import com.learninge.proto.app.v1.LogoutResponse;
import com.learninge.proto.app.v1.RegisterRequest;
import com.learninge.proto.app.v1.ResetPasswordRequest;
import com.learninge.proto.app.v1.UpdateMeRequest;
import com.learninge.proto.app.v1.UpdateMeResponse;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.AuthBackendGrpc.AuthBackendBlockingStub;
import com.learninge.proto.internal.v1.BackendLogoutRequest;
import com.learninge.server.common.grpc.CallerContext;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.Unary;
import com.learninge.server.edge.security.Identity;
import com.learninge.server.edge.security.TokenAuthenticator;
import io.grpc.stub.StreamObserver;

/**
 * EP-02..EP-10. RPC thu hồi token xóa cache ngay, kể cả khi lời gọi lỗi: hết thời gian chờ không có
 * nghĩa là auth chưa làm.
 */
public final class AuthForwarder extends AuthServiceGrpc.AuthServiceImplBase {
  private final InternalChannels channels;
  private final TokenAuthenticator tokens;
  private final GetMeComposer getMe;

  public AuthForwarder(InternalChannels channels, TokenAuthenticator tokens, GetMeComposer getMe) {
    this.channels = channels;
    this.tokens = tokens;
    this.getMe = getMe;
  }

  private AuthBackendBlockingStub auth() {
    return Downstream.timed(
        AuthBackendGrpc.newBlockingStub(channels.to("auth")), RpcDeadlines.AUTH);
  }

  @Override
  public void register(RegisterRequest request, StreamObserver<AuthSession> observer) {
    Unary.reply(observer, Downstream.call(() -> auth().register(request)));
  }

  @Override
  public void login(LoginRequest request, StreamObserver<AuthSession> observer) {
    Unary.reply(observer, Downstream.call(() -> auth().login(request)));
  }

  @Override
  public void logout(LogoutRequest request, StreamObserver<LogoutResponse> observer) {
    Identity identity = Identity.KEY.get();
    BackendLogoutRequest internal =
        BackendLogoutRequest.newBuilder().setTokenSha256(identity.tokenSha256()).build();
    LogoutResponse response;
    try {
      response = Downstream.call(() -> auth().logout(internal));
    } finally {
      tokens.invalidateToken(identity);
    }
    Unary.reply(observer, response);
  }

  @Override
  public void getMe(GetMeRequest request, StreamObserver<GetMeResponse> observer) {
    Unary.reply(observer, getMe.compose());
  }

  @Override
  public void updateMe(UpdateMeRequest request, StreamObserver<UpdateMeResponse> observer) {
    Unary.reply(observer, Downstream.call(() -> auth().updateMe(request)));
  }

  @Override
  public void changePassword(
      ChangePasswordRequest request, StreamObserver<ChangePasswordResponse> observer) {
    ChangePasswordResponse response;
    try {
      response = Downstream.call(() -> auth().changePassword(request));
    } finally {
      tokens.invalidateUser(CallerContext.userId());
    }
    Unary.reply(observer, response);
  }

  @Override
  public void getResetQuestion(
      GetResetQuestionRequest request, StreamObserver<GetResetQuestionResponse> observer) {
    Unary.reply(observer, Downstream.call(() -> auth().getResetQuestion(request)));
  }

  @Override
  public void resetPassword(ResetPasswordRequest request, StreamObserver<AuthSession> observer) {
    AuthSession session = Downstream.call(() -> auth().resetPassword(request));
    tokens.invalidateUser(session.getUser().getId());
    Unary.reply(observer, session);
  }

  @Override
  public void deleteAccount(
      DeleteAccountRequest request, StreamObserver<DeleteAccountResponse> observer) {
    DeleteAccountResponse response;
    try {
      response = Downstream.call(() -> auth().deleteAccount(request));
    } finally {
      tokens.invalidateUser(CallerContext.userId());
    }
    Unary.reply(observer, response);
  }
}
