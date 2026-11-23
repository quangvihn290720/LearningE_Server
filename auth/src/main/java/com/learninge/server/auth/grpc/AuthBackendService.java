package com.learninge.server.auth.grpc;

import com.learninge.proto.app.v1.AuthSession;
import com.learninge.proto.app.v1.ChangePasswordRequest;
import com.learninge.proto.app.v1.ChangePasswordResponse;
import com.learninge.proto.app.v1.DeleteAccountRequest;
import com.learninge.proto.app.v1.DeleteAccountResponse;
import com.learninge.proto.app.v1.GetResetQuestionRequest;
import com.learninge.proto.app.v1.GetResetQuestionResponse;
import com.learninge.proto.app.v1.LoginRequest;
import com.learninge.proto.app.v1.LogoutResponse;
import com.learninge.proto.app.v1.RegisterRequest;
import com.learninge.proto.app.v1.ResetPasswordRequest;
import com.learninge.proto.app.v1.UpdateMeRequest;
import com.learninge.proto.app.v1.UpdateMeResponse;
import com.learninge.proto.app.v1.User;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.BackendLogoutRequest;
import com.learninge.proto.internal.v1.ExportProfilesRequest;
import com.learninge.proto.internal.v1.ExportProfilesResponse;
import com.learninge.proto.internal.v1.GetProfileRequest;
import com.learninge.proto.internal.v1.GetProfileResponse;
import com.learninge.proto.internal.v1.PublicProfile;
import com.learninge.server.auth.service.AccountService;
import com.learninge.server.auth.service.AccountService.Registration;
import com.learninge.server.auth.service.LoginService;
import com.learninge.server.auth.service.ResetService;
import com.learninge.server.auth.service.Session;
import com.learninge.server.auth.service.SessionService;
import com.learninge.server.auth.store.UserRow;
import com.learninge.server.common.grpc.CallerContext;
import com.learninge.server.common.grpc.Unary;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;

/** Chỉ đổi message ↔ kiểu miền; nghiệp vụ nằm ở gói {@code service}. */
public final class AuthBackendService extends AuthBackendGrpc.AuthBackendImplBase {
  private static final int MAX_EXPORT = 1000;

  private final AccountService accounts;
  private final LoginService login;
  private final ResetService reset;
  private final SessionService sessions;

  public AuthBackendService(
      AccountService accounts, LoginService login, ResetService reset, SessionService sessions) {
    this.accounts = accounts;
    this.login = login;
    this.reset = reset;
    this.sessions = sessions;
  }

  @Override
  public void register(RegisterRequest request, StreamObserver<AuthSession> observer) {
    Registration registration =
        new Registration(
            request.getEmail(),
            request.getPassword(),
            request.getNickname(),
            request.getDisplayName(),
            request.getSecurityQuestionId(),
            request.getSecurityAnswer(),
            request.getDeviceName());
    Unary.reply(observer, toProto(accounts.register(registration)));
  }

  @Override
  public void login(LoginRequest request, StreamObserver<AuthSession> observer) {
    Session session =
        login.login(request.getEmail(), request.getPassword(), request.getDeviceName());
    Unary.reply(observer, toProto(session));
  }

  @Override
  public void logout(BackendLogoutRequest request, StreamObserver<LogoutResponse> observer) {
    sessions.revoke(TokenValidatorService.requireSha256(request.getTokenSha256()));
    Unary.reply(observer, LogoutResponse.getDefaultInstance());
  }

  @Override
  public void getProfile(GetProfileRequest request, StreamObserver<GetProfileResponse> observer) {
    UserRow user = accounts.profile(CallerContext.userId());
    Unary.reply(observer, GetProfileResponse.newBuilder().setUser(toProto(user)).build());
  }

  @Override
  public void updateMe(UpdateMeRequest request, StreamObserver<UpdateMeResponse> observer) {
    UserRow user =
        accounts.updateProfile(
            CallerContext.userId(),
            request.hasNickname() ? Optional.of(request.getNickname()) : Optional.empty(),
            request.hasDisplayName() ? Optional.of(request.getDisplayName()) : Optional.empty());
    Unary.reply(observer, UpdateMeResponse.newBuilder().setUser(toProto(user)).build());
  }

  @Override
  public void changePassword(
      ChangePasswordRequest request, StreamObserver<ChangePasswordResponse> observer) {
    Session session =
        accounts.changePassword(
            CallerContext.userId(),
            request.getCurrentPassword(),
            request.getNewPassword(),
            request.getDeviceName());
    Unary.reply(
        observer,
        ChangePasswordResponse.newBuilder()
            .setToken(session.token().value())
            .setExpiresAtMs(session.expiresAt())
            .build());
  }

  @Override
  public void getResetQuestion(
      GetResetQuestionRequest request, StreamObserver<GetResetQuestionResponse> observer) {
    int questionId = reset.questionFor(request.getEmail());
    Unary.reply(
        observer, GetResetQuestionResponse.newBuilder().setSecurityQuestionId(questionId).build());
  }

  @Override
  public void resetPassword(ResetPasswordRequest request, StreamObserver<AuthSession> observer) {
    Session session =
        reset.reset(
            request.getEmail(),
            request.getSecurityAnswer(),
            request.getNewPassword(),
            request.getDeviceName());
    Unary.reply(observer, toProto(session));
  }

  @Override
  public void deleteAccount(
      DeleteAccountRequest request, StreamObserver<DeleteAccountResponse> observer) {
    accounts.deleteAccount(CallerContext.userId(), request.getPassword());
    Unary.reply(observer, DeleteAccountResponse.getDefaultInstance());
  }

  @Override
  public void exportProfiles(
      ExportProfilesRequest request, StreamObserver<ExportProfilesResponse> observer) {
    int limit = Math.clamp(request.getLimit(), 1, MAX_EXPORT);
    var profiles = accounts.exportProfiles(request.getAfterId(), limit + 1);
    boolean hasMore = profiles.size() > limit;
    var page = hasMore ? profiles.subList(0, limit) : profiles;
    List<PublicProfile> messages =
        page.stream()
            .map(
                p ->
                    PublicProfile.newBuilder()
                        .setUserId(p.uuid())
                        .setNickname(p.nickname())
                        .setProfileVersion(p.version())
                        .build())
            .toList();
    Unary.reply(
        observer,
        ExportProfilesResponse.newBuilder()
            .addAllProfiles(messages)
            .setNextAfterId(page.isEmpty() ? request.getAfterId() : page.getLast().id())
            .setHasMore(hasMore)
            .build());
  }

  private static AuthSession toProto(Session session) {
    return AuthSession.newBuilder()
        .setToken(session.token().value())
        .setExpiresAtMs(session.expiresAt())
        .setUser(toProto(session.user()))
        .build();
  }

  private static User toProto(UserRow user) {
    return User.newBuilder()
        .setId(user.uuid())
        .setEmail(user.email())
        .setNickname(user.nickname())
        .setDisplayName(user.displayName())
        .setCreatedAtMs(user.createdAt())
        .build();
  }
}
