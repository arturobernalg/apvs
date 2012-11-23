package ch.cern.atlas.apvs.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import org.asteriskjava.live.AsteriskChannel;
import org.asteriskjava.live.AsteriskServer;
import org.asteriskjava.live.DefaultAsteriskServer;
import org.asteriskjava.live.LiveException;
import org.asteriskjava.live.MeetMeRoom;
import org.asteriskjava.live.OriginateCallback;
import org.asteriskjava.manager.AuthenticationFailedException;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.ManagerConnectionFactory;
import org.asteriskjava.manager.ManagerEventListener;
import org.asteriskjava.manager.TimeoutException;
import org.asteriskjava.manager.action.HangupAction;
import org.asteriskjava.manager.action.SipPeersAction;
import org.asteriskjava.manager.event.BridgeEvent;
import org.asteriskjava.manager.event.ConnectEvent;
import org.asteriskjava.manager.event.DisconnectEvent;
import org.asteriskjava.manager.event.HangupEvent;
import org.asteriskjava.manager.event.ManagerEvent;
import org.asteriskjava.manager.event.MeetMeEndEvent;
import org.asteriskjava.manager.event.MeetMeJoinEvent;
import org.asteriskjava.manager.event.MeetMeLeaveEvent;
import org.asteriskjava.manager.event.NewChannelEvent;
import org.asteriskjava.manager.event.PeerEntryEvent;
import org.asteriskjava.manager.event.PeerStatusEvent;

import ch.cern.atlas.apvs.client.AudioException;
import ch.cern.atlas.apvs.client.domain.Conference;
import ch.cern.atlas.apvs.client.event.AsteriskStatusRemoteEvent;
import ch.cern.atlas.apvs.client.event.AudioSettingsChangedRemoteEvent;
import ch.cern.atlas.apvs.client.event.ConnectionStatusChangedRemoteEvent;
import ch.cern.atlas.apvs.client.event.ConnectionStatusChangedRemoteEvent.ConnectionType;
import ch.cern.atlas.apvs.client.event.MeetMeRemoteEvent;
import ch.cern.atlas.apvs.client.service.AudioService;
import ch.cern.atlas.apvs.client.settings.AudioSettings;
import ch.cern.atlas.apvs.client.settings.ConferenceRooms;
import ch.cern.atlas.apvs.eventbus.shared.ConnectionUUIDsChangedEvent;
import ch.cern.atlas.apvs.eventbus.shared.RemoteEventBus;
import ch.cern.atlas.apvs.eventbus.shared.RequestRemoteEvent;

public class AudioServiceImpl extends ResponsePollService implements
		AudioService, ManagerEventListener {

	private static final long serialVersionUID = 1L;

	private ManagerConnection managerConnection;

	private AsteriskServer asteriskServer;
	private AudioSettings voipAccounts;
	private ConferenceRooms conferenceRooms;

	private ArrayList<String> usersList;

	private ScheduledExecutorService executorService;
	private ScheduledFuture<?> connectFuture;
	private boolean audioOk;
	private boolean asteriskConnected;
	private AsteriskPing ping;

	// Account Details
	private static final String ASTERISK_URL = "pcatlaswpss01.cern.ch";
	private static final String AMI_ACCOUNT = "manager";
	private static final String PASSWORD = "password";

	// Asterisk Placing Calls Details
	private static final String CONTEXT = "internal";
	private static final int PRIORITY = 1;
	private static final int TIMEOUT = 20000;
	private static final long ASTERISK_POOLING = 20000;

	private static RemoteEventBus eventBus;

	public AudioServiceImpl() {
		if (eventBus != null)
			return;
		System.out.println("Creating AudioService...");
		eventBus = APVSServerFactory.getInstance().getEventBus();
		executorService = Executors.newSingleThreadScheduledExecutor();

		RequestRemoteEvent.register(eventBus, new RequestRemoteEvent.Handler() {

			@Override
			public void onRequestEvent(RequestRemoteEvent event) {
				String type = event.getRequestedClassName();

				if (type.equals(ConnectionStatusChangedRemoteEvent.class.getName())) {
					ConnectionStatusChangedRemoteEvent.fire(eventBus,
							ConnectionType.audio, audioOk);
				}
			}
		});
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		System.out.println("Starting Audio Service...");

		voipAccounts = new AudioSettings();
		conferenceRooms = new ConferenceRooms();
		audioOk = false;
		asteriskConnected = false;

		// Asterisk Connection Manager
		ManagerConnectionFactory factory = new ManagerConnectionFactory(
				ASTERISK_URL, AMI_ACCOUNT, PASSWORD);
		this.managerConnection = factory.createManagerConnection();

		// Eases the communication with asterisk server
		asteriskServer = new DefaultAsteriskServer(managerConnection);

		// Event handler
		managerConnection.addEventListener(this);
		
		ping  = new AsteriskPing(managerConnection);

		this.connectFuture = executorService.scheduleAtFixedRate(
				new Runnable() {

					@Override
					public void run() {
						if (!asteriskConnected) {
							System.err
									.println("Trying login in Asterisk Server on "
											+ ASTERISK_URL.toLowerCase()
											+ " ...");
							try {
								login();
							} catch (AudioException e) {
								System.err.println("Fail to login: "
										+ e.getMessage());
							}
						}
												
						if (ping.isAlive()) {
							audioOk = true;
							ConnectionStatusChangedRemoteEvent.fire(eventBus,ConnectionType.audio, audioOk);
						} else {
							audioOk = false;
							System.err.println("Asterisk Server is not available...");
							ConnectionStatusChangedRemoteEvent.fire(eventBus,ConnectionType.audio, audioOk);
						}
					}
				}, 0, ASTERISK_POOLING, TimeUnit.MILLISECONDS);
		
		// FOR #281, uids may still change in the future, related to #284, at this moment any reload generated more uids... none is taken away on disconnect
		ConnectionUUIDsChangedEvent.subscribe(eventBus, new ConnectionUUIDsChangedEvent.Handler() {
			
			@Override
			public void onConnectionUUIDchanged(ConnectionUUIDsChangedEvent event) {
				System.err.println("Supervisor Connect or Disconnect "+event);
			}
		});

		AudioSettingsChangedRemoteEvent.subscribe(eventBus,
				new AudioSettingsChangedRemoteEvent.Handler() {
			
					@Override
					public void onAudioSettingsChanged(
							AudioSettingsChangedRemoteEvent event) {
						voipAccounts = event.getAudioSettings();
					}
				});

		MeetMeRemoteEvent.subscribe(eventBus, new MeetMeRemoteEvent.Handler() {

			@Override
			public void onMeetMeEvent(MeetMeRemoteEvent event) {
				conferenceRooms = event.getConferenceRooms();
			}
		});

	}

	// *********************************************
	// Constructor

	public void login() throws AudioException {
		try {
			managerConnection.login();
		} catch (IllegalStateException e) {
			throw new AudioException(e.getMessage());
		} catch (IOException e) {
			throw new AudioException(e.getMessage());
		} catch (AuthenticationFailedException e) {
			throw new AudioException("Failed login to Asterisk Manager: "
					+ e.getMessage());
		} catch (TimeoutException e) {
			throw new AudioException("Login to Asterisk Timeout: "
					+ e.getMessage());
		}
	}

	public String filterNumber(String channel) {
		return channel.substring(0, channel.indexOf("-"));
	}

	/*********************************************
	 * RPC Methods
	 *********************************************/
	@Override
	public void call(String callerOriginater, String callerDestination) {
		asteriskServer.originateToExtension(callerOriginater, CONTEXT,
				callerDestination, PRIORITY, TIMEOUT);
	}

	@Override
	public void hangup(String channel) throws AudioException {
		HangupAction hangupCall = new HangupAction(channel);
		try {
			managerConnection.sendAction(hangupCall);
		} catch (IllegalArgumentException e) {
			throw new AudioException(e.getMessage());
		} catch (IllegalStateException e) {
			throw new AudioException(e.getMessage());
		} catch (IOException e) {
			throw new AudioException(e.getMessage());
		} catch (TimeoutException e) {
			throw new AudioException("Timeout: " + e.getMessage());
		}
	}

	@Override
	public void hangupMultiple(List<String> channels) throws AudioException {
		for (int i = 0; i < channels.size(); i++) {
			hangup(channels.get(i));
		}
	}

	@Override
	public void newConference(List<String> participantsNumber) {	
		MeetMeRoom room = asteriskServer.getMeetMeRoom(conferenceRooms.newRoom());
		for (int i = 0; i < participantsNumber.size(); i++) {
			addToConference(participantsNumber.get(i), room.getRoomNumber()
					+ ",qd");
		}
	}

	@Override
	public void addToConference(String participant, String roomAndParam) {
		asteriskServer.originateToApplicationAsync(participant, "MeetMe",
				roomAndParam, TIMEOUT, new OriginateCallback() {

					@Override
					public void onSuccess(AsteriskChannel arg0) {
						System.err.println("User added to conference call...");
					}

					@Override
					public void onNoAnswer(AsteriskChannel arg0) {
						System.err.println("No answer...");
					}

					@Override
					public void onFailure(LiveException arg0) {
						System.err.println("Failure to load...");
					}

					@Override
					public void onDialing(AsteriskChannel arg0) {
						System.err.println("User currently dialing someone...");
					}

					@Override
					public void onBusy(AsteriskChannel arg0) {
						System.err.println("User Busy...");
					}
				});
	}

	@Override
	public void usersList() throws AudioException {
		usersList = new ArrayList<String>();
		try {
			managerConnection.sendAction(new SipPeersAction());
		} catch (IllegalArgumentException e) {
			throw new AudioException(e.getMessage());
		} catch (IllegalStateException e) {
			throw new AudioException(e.getMessage());
		} catch (IOException e) {
			throw new AudioException(e.getMessage());
		} catch (TimeoutException e) {
			throw new AudioException("Timeout: " + e.getMessage());
		}
	}

	/*********************************************
	 * Event Handler
	 *********************************************/

	@Override
	public void onManagerEvent(ManagerEvent event) {
		// NewChannelEvent
		if (event instanceof NewChannelEvent) {
			NewChannelEvent channel = (NewChannelEvent) event;
			newChannelEvent(channel);
			// BridgeEvent
		} else if (event instanceof BridgeEvent) {
			BridgeEvent bridge = (BridgeEvent) event;
			bridgeEvent(bridge);
			// HangupEvent
		} else if (event instanceof HangupEvent) {
			HangupEvent hangup = (HangupEvent) event;
			hangupEvent(hangup);
			// PeerStatusEvent
		} else if (event instanceof PeerStatusEvent) {
			PeerStatusEvent peer = (PeerStatusEvent) event;
			peerStatusEvent(peer);
			// MeetMeJoinEvent
		} else if (event instanceof MeetMeJoinEvent) {
			MeetMeJoinEvent meetJoin = (MeetMeJoinEvent) event;
			meetMeJoin(meetJoin);
			// MeetMeLeaveEvent
		} else if (event instanceof MeetMeLeaveEvent) {
			MeetMeLeaveEvent meetLeave = (MeetMeLeaveEvent) event;
			meetMeLeave(meetLeave);
			// MeetMeEndEvent
		} else if (event instanceof MeetMeEndEvent) {
			MeetMeEndEvent meetEnd = (MeetMeEndEvent) event;
			meetMeEnd(meetEnd);
			// PeerEntryEvent
		} else if (event instanceof PeerEntryEvent) {
			PeerEntryEvent peer = (PeerEntryEvent) event;
			peerEntryEvent(peer);
			// ConnectEvent
		} else if (event instanceof ConnectEvent) {
			asteriskConnected = true;
			System.out.println("Connected to Asterisk server");
			// DisconnectEvent
		} else if (event instanceof DisconnectEvent) {
			System.out.println("Disconnected from Asterisk server");
		}
	}

	/*********************************************
	 * Event Methods
	 *********************************************/
	// New Channel
	public void newChannelEvent(NewChannelEvent event) {
		String channel = event.getChannel();
		String number = filterNumber(channel);
		String ptuId = voipAccounts.getPtuId(number);

		if (ptuId != null) {
			voipAccounts.setChannel(ptuId, channel);
			((RemoteEventBus) eventBus)
					.fireEvent(new AudioSettingsChangedRemoteEvent(voipAccounts));
			return;
		}
		System.err.println("NO PTU FOUND WITH NUMBER " + number);
	}

	// Bridge of Call Channels - Event only valid for private call
	public void bridgeEvent(BridgeEvent event) {
		String channel1 = event.getChannel1();
		String number1 = filterNumber(channel1);
		String ptuId1 = voipAccounts.getPtuId(number1);

		String channel2 = event.getChannel2();
		String number2 = filterNumber(channel2);
		String ptuId2 = voipAccounts.getPtuId(number2);

		if (ptuId1 != null && ptuId2 != null) {
			voipAccounts.setDestPTUser(ptuId1,
					voipAccounts.getUsername(ptuId2), ptuId2);
			voipAccounts.setOnCall(ptuId1, true);

			voipAccounts.setDestPTUser(ptuId2,
					voipAccounts.getUsername(ptuId1), ptuId1);
			voipAccounts.setOnCall(ptuId2, true);
			((RemoteEventBus) eventBus)
					.fireEvent(new AudioSettingsChangedRemoteEvent(voipAccounts));
			return;
		}
		System.err.println("NO PTUS FOUND WITH NUMBERS " + number1 + " & "
				+ number2);
	}

	// Hangup Call Event
	public void hangupEvent(HangupEvent event) {
		String channel = event.getChannel();
		String number = filterNumber(channel);
		String ptuId = voipAccounts.getPtuId(number);
		if (ptuId != null) {
			voipAccounts.setChannel(ptuId, "");
			voipAccounts.setDestPTUser(ptuId, "", "");
			voipAccounts.setOnCall(ptuId, false);
			voipAccounts.setOnConference(ptuId, false);
			voipAccounts.setRoom(ptuId, "");
			((RemoteEventBus) eventBus)
					.fireEvent(new AudioSettingsChangedRemoteEvent(voipAccounts));
			return;
		}
		System.err.println("NO PTU FOUND WITH NUMBER " + number);
	}

	// Users Register and Unregister
	public void peerStatusEvent(PeerStatusEvent event) {
		String status = event.getPeerStatus();
		if (status.equals("Registered"))
			status = "Online";
		else
			status = "Offline";

		String number = event.getPeer();
		String ptuId = voipAccounts.getPtuId(number);
		if (ptuId != null) {
			voipAccounts.setStatus(ptuId, status);
			((RemoteEventBus) eventBus)
					.fireEvent(new AudioSettingsChangedRemoteEvent(voipAccounts));
			return;
		}
		System.err.println("NO PTU FOUND OR ASSIGNED WITH NUMBER " + number);
	}

	// MeetMe Join Event
	public void meetMeJoin(MeetMeJoinEvent event) {
		String channel = event.getChannel();
		String room = event.getMeetMe();
		String number = filterNumber(channel);
		String ptuId = voipAccounts.getPtuId(number);

		if (!conferenceRooms.roomExist(room)) {
			System.out.println(room);
			conferenceRooms.put(room, new Conference());		
			conferenceRooms.get(room).setActivity(
					voipAccounts.getActivity(ptuId));
		}

		if (ptuId != null) {
			voipAccounts.setChannel(ptuId, channel);
			voipAccounts.setRoom(ptuId, room);
			voipAccounts.setOnConference(ptuId, true);
			conferenceRooms.get(room).setUserNum(
					conferenceRooms.get(room).getUserNum() + 1);
			conferenceRooms.get(room).addPtu(ptuId);
			conferenceRooms.get(room).addUsername(
					voipAccounts.getUsername(ptuId));

			((RemoteEventBus) eventBus)
					.fireEvent(new AudioSettingsChangedRemoteEvent(voipAccounts));
			((RemoteEventBus) eventBus).fireEvent(new MeetMeRemoteEvent(
					conferenceRooms));
			return;
		}

		System.err.println("NO PTU FOUND WITH NUMBER " + number);
	}

	// MeetMe Leave Event
	public void meetMeLeave(MeetMeLeaveEvent event) {

		String channel = event.getChannel();
		String room = event.getMeetMe();
		String number = filterNumber(channel);
		String ptuId = voipAccounts.getPtuId(number);
		if (ptuId != null) {
			if (voipAccounts.getChannel(ptuId).equals(channel))
				voipAccounts.setChannel(ptuId, "");
			if (voipAccounts.getRoom(ptuId).equals(room))
				voipAccounts.setRoom(ptuId, "");

			voipAccounts.setOnConference(ptuId, false);

			conferenceRooms.get(room).setUserNum(
					conferenceRooms.get(room).getUserNum() - 1);
			int index = conferenceRooms.get(room).getPtuIds().indexOf(ptuId);
			conferenceRooms.get(room).getPtuIds().remove(index);
			conferenceRooms.get(room).getUsernames().remove(index);

			((RemoteEventBus) eventBus)
					.fireEvent(new AudioSettingsChangedRemoteEvent(voipAccounts));
			((RemoteEventBus) eventBus).fireEvent(new MeetMeRemoteEvent(
					conferenceRooms));
			return;
		}

		System.err.println("NO PTU FOUND WITH NUMBER " + number);
	}

	// MeetMe End Event
	public void meetMeEnd(MeetMeEndEvent event) {
		String room = event.getMeetMe();
		conferenceRooms.remove(room);
	}

	// Peer Entry Event
	public void peerEntryEvent(PeerEntryEvent event) {
		String number = "SIP/" + event.getObjectName();
		usersList.add(number);
		((RemoteEventBus) eventBus)
				.fireEvent(new AsteriskStatusRemoteEvent(usersList));
	}

}
