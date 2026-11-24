package com.learninge.server.edge.forward;

import com.learninge.proto.app.v1.ContentServiceGrpc;
import com.learninge.proto.app.v1.GetDeckRequest;
import com.learninge.proto.app.v1.GetDeckResponse;
import com.learninge.proto.app.v1.GetDecksRequest;
import com.learninge.proto.app.v1.GetDecksResponse;
import com.learninge.proto.app.v1.GetExamRequest;
import com.learninge.proto.app.v1.GetExamResponse;
import com.learninge.proto.app.v1.GetExamsRequest;
import com.learninge.proto.app.v1.GetExamsResponse;
import com.learninge.proto.app.v1.GetManifestRequest;
import com.learninge.proto.app.v1.GetManifestResponse;
import com.learninge.proto.internal.v1.ContentBackendGrpc;
import com.learninge.proto.internal.v1.ContentBackendGrpc.ContentBackendBlockingStub;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.Unary;
import io.grpc.stub.StreamObserver;

/**
 * EP-14..EP-16, EP-20, EP-21.
 */
public final class ContentForwarder extends ContentServiceGrpc.ContentServiceImplBase {
    private final InternalChannels channels;

    public ContentForwarder(InternalChannels channels) {
        this.channels = channels;
    }

    private ContentBackendBlockingStub content() {
        return Downstream.timed(
                ContentBackendGrpc.newBlockingStub(channels.to("content")), RpcDeadlines.DEFAULT);
    }

    @Override
    public void getManifest(
            GetManifestRequest request, StreamObserver<GetManifestResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> content().getManifest(request)));
    }

    @Override
    public void getDeck(GetDeckRequest request, StreamObserver<GetDeckResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> content().getDeck(request)));
    }

    @Override
    public void getExam(GetExamRequest request, StreamObserver<GetExamResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> content().getExam(request)));
    }

    @Override
    public void getDecks(GetDecksRequest request, StreamObserver<GetDecksResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> content().getDecks(request)));
    }

    @Override
    public void getExams(GetExamsRequest request, StreamObserver<GetExamsResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> content().getExams(request)));
    }
}
