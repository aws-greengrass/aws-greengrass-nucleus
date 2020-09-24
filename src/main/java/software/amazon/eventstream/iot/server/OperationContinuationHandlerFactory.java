package software.amazon.eventstream.iot.server;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuationHandler;

/**
 * This is really the entire service interface base class
 */
public interface OperationContinuationHandlerFactory {
    Function<OperationContinuationHandlerContext, ? extends ServerConnectionContinuationHandler> getOperationHandler(final String operationName);
    Set<String> getAllOperations();
    boolean hasHandlerForOperation(String operation);
    default void validateAllOperationsSet() {
        if (!getAllOperations().stream().allMatch(op -> hasHandlerForOperation(op))) {
            String unmappedOperations = getAllOperations().stream()
                    .filter(op -> !hasHandlerForOperation(op)).collect(Collectors.joining(","));
            throw new IllegalStateException(this.getClass().getName() +
                    " does not have all operations mapped! Unmapped operations: {" + unmappedOperations + "}");
        }
    }
}
