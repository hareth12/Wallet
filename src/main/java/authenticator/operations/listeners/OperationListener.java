package authenticator.operations.listeners;

import javax.annotation.Nullable;

public interface OperationListener {
	// progress
	public void onBegin(String str);
	public void statusReport(String report);
	public void onFinished(String str);
	public void onError(@Nullable Exception e, @Nullable Throwable t);
}