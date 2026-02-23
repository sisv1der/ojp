package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultSetFetchRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.updateSessionActivity;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

@Slf4j
public class FetchNextRowsAction implements Action<ResultSetFetchRequest, OpResult> {

    private static final FetchNextRowsAction INSTANCE = new FetchNextRowsAction();

    private FetchNextRowsAction() {
        // Private constructor prevents external instantiation
    }

    public static FetchNextRowsAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing fetch next rows for result set  {}", request.getResultSetUUID());

        // Update session activity
        updateSessionActivity(context, request.getSession());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            ConnectionSessionDTO dto = sessionConnection(context, request.getSession(), false);
            handleResultSet(context, dto.getSession(), request.getResultSetUUID(), responseObserver);
        } catch (SQLException e) {
            log.error("Failure fetch next rows for result set: {}", e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }
}
