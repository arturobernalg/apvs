package ch.cern.atlas.apvs.eventbus.shared;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

public class RemoteEventBusIdsChangedEvent extends RemoteEvent<RemoteEventBusIdsChangedEvent.Handler> {

	private static final long serialVersionUID = 1483151980311290676L;

	public interface Handler {
		/**
		 * Called when an event is fired.
		 * 
		 * @param event
		 *            an {@link MessageReceivedEvent} instance
		 */
		void onRemoteIdsChanged(RemoteEventBusIdsChangedEvent event);
	}

	private static final Type<RemoteEventBusIdsChangedEvent.Handler> TYPE = new Type<RemoteEventBusIdsChangedEvent.Handler>();

	/**
	 * Register a handler for events on the eventbus.
	 * 
	 * @param eventBus
	 *            the {@link EventBus}
	 * @param handler
	 *            an Handler instance
	 * @return an {@link HandlerRegistration} instance
	 */
	public static HandlerRegistration register(RemoteEventBus eventBus,
			RemoteEventBusIdsChangedEvent.Handler handler) {
		return eventBus.addHandler(TYPE, handler);
	}
	
	public static HandlerRegistration subscribe(Object src, RemoteEventBus eventBus, Handler handler) throws SerializationException {
		HandlerRegistration registration = register(eventBus, handler);
		
		eventBus.fireEvent(new RequestRemoteEvent(src, RemoteEventBusIdsChangedEvent.class));
		
		return registration;
	}

	
	private ArrayList<Long> remoteIds;
	
	public RemoteEventBusIdsChangedEvent() {
	}

	public RemoteEventBusIdsChangedEvent(Object src, ArrayList<Long> remoteIds) {
		super(src);
		this.remoteIds = remoteIds;
	}

	@Override
	public Type<RemoteEventBusIdsChangedEvent.Handler> getAssociatedType() {
		return TYPE;
	}

	public List<Long> getClientIds() {
		return remoteIds;
	}
	
	@Override
	protected void dispatch(Handler handler) {
		handler.onRemoteIdsChanged(this);
	}
	
	@Override
	public String toString() {
		return "RemoteIdsChangedEvent "+remoteIds.size();
	}
}
