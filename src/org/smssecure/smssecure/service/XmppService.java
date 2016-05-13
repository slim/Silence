package org.smssecure.smssecure.service;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.jobs.SmsReceiveJob;
import org.smssecure.smssecure.jobs.SmsSentJob;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.service.SmsDeliveryListener;
import org.smssecure.smssecure.transport.UndeliverableMessageException;
import org.smssecure.smssecure.util.ServiceUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.XmppUtil;
import org.smssecure.smssecure.util.XmppUtil.XmppServer;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.roster.RosterListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener;

import java.util.ArrayList;
import java.util.Collection;

public class XmppService extends Service {
  private static final String TAG = XmppService.class.getSimpleName();

  private static XmppService instance;

  public static final int    SERVICE_RUNNING_ID      = 4142;
  public static final String XMPP_CONNECTIVITY_EVENT = "org.smssecure.smssecure.XMPP_CONNECTIVITY_UPDATE";

  public static int DISCONNECTED = 0;
  public static int CONNECTED    = 1;

  private XMPPTCPConnection connection;
  private int connectionStatus = DISCONNECTED;
  private XmppServer registeredXmppServer;

  @Override
  public void onCreate() {
    Log.w(TAG, "onCreate()");
    instance = this;

    if (!SilencePreferences.isXmppRegistered(this)) {
      Log.w(TAG, "Xmpp is disabled. Exiting...");
      stopSelf();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.w(TAG, "onStartCommand(): received start id " + startId + ": " + intent);
    if (SilencePreferences.isXmppRegistered(this)) {
      new AsyncTask<XmppService, Void, Void>() {
        @Override
        protected Void doInBackground(XmppService... params) {
          XmppService instance = params[0];
          instance.registeredXmppServer = XmppUtil.getRegisteredServer((Context) instance);

          XMPPTCPConnectionConfiguration.Builder configBuilder = XMPPTCPConnectionConfiguration.builder();
          configBuilder.setServiceName(instance.registeredXmppServer.getHostname())
                       .setPort(registeredXmppServer.getPort())
                       .setUsernameAndPassword(instance.registeredXmppServer.getUsername(), instance.registeredXmppServer.getPassword())
                       .setResource(instance.getString(R.string.app_name));
          instance.connection = new XMPPTCPConnection(configBuilder.setConnectTimeout(XmppUtil.XMPP_TIMEOUT).build());
          instance.connection.setPacketReplyTimeout(XmppUtil.XMPP_STANZA_TIMEOUT);
          instance.connection.setUseStreamManagementDefault(true);
          instance.connection.setUseStreamManagement(true);
          instance.connection.addConnectionListener(new XmppServiceConnectionListener(instance));
          DeliveryReceiptManager deliveryReceiptManager = DeliveryReceiptManager.getInstanceFor(instance.connection);
          deliveryReceiptManager.autoAddDeliveryReceiptRequests();
          deliveryReceiptManager.addReceiptReceivedListener(new XmppServiceDeliveryReceiptListener(instance));
          instance.connection.addPacketListener(new XmppServiceDeliveryReceiptDemandListener(instance), new PacketTypeFilter(Message.class));
          try {
            instance.connection.connect();
            instance.connection.login();
            ReconnectionManager.getInstanceFor(instance.connection).enableAutomaticReconnection();
            Roster.getInstanceFor(instance.connection).addRosterListener(new XmppRosterListener(instance));
            Log.w(TAG, "isSmAvailable(): " + instance.connection.isSmAvailable());
            Log.w(TAG, "isSmEnabled(): " + instance.connection.isSmEnabled());
          } catch (Exception e) {
            Log.w(TAG, e);
            instance.updateXmppStatus(XmppService.DISCONNECTED);
            instance.displayFailureNotification();
          }
          return null;
        }
      }.execute(this);
    }

    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    Log.w(TAG, "onDestroy()");
    if (connection != null) {
      Log.w(TAG, "connection is not null, disconnecting...");
      new AsyncTask<XmppService, Void, Void>() {
        @Override
        protected Void doInBackground(XmppService... params) {
          params[0].connection.disconnect();
          return null;
        }
      }.execute(this);
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  public static @Nullable XmppService getInstance() {
    return instance;
  }

  public int getConnectionStatus() {
    return connectionStatus;
  }

  private void displayFailureNotification() {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this);

    Intent       targetIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
    Notification notification = new NotificationCompat.Builder(this)
                                    .setSmallIcon(R.drawable.ic_warning_dark)
                                    .setContentTitle(getString(R.string.XmppService_xmpp_connection_failed))
                                    .setContentText(getString(R.string.XmppService_xmpp_features_in_silence_are_disabled))
                                    .setAutoCancel(true)
                                    .setContentIntent(PendingIntent.getActivity(this, 0,
                                                                                targetIntent,
                                                                                PendingIntent.FLAG_UPDATE_CURRENT))
                                    .setPriority(Notification.PRIORITY_DEFAULT)
                                    .build();

    ServiceUtil.getNotificationManager(this).notify(SERVICE_RUNNING_ID, notification);
  }

  public String send(String body, Recipient recipient)
    throws UndeliverableMessageException
  {
    if (recipient.getXmppJid() == null) throw new UndeliverableMessageException("JID for " + recipient.getNumber() + " is null");

    ChatManager chatmanager = ChatManager.getInstanceFor(connection);
    Chat        chat        = chatmanager.createChat(recipient.getXmppJid(), null);

    try {
      Message message = new Message(recipient.getXmppJid(), body);
      String deliveryReceiptId = null;
      if (SilencePreferences.isSmsDeliveryReportsEnabled(this)) {
        deliveryReceiptId = DeliveryReceiptRequest.addTo(message);
      }
    	chat.sendMessage(message);
      return deliveryReceiptId;
    }
    catch (Exception e) {
    	throw new UndeliverableMessageException(e);
    }
  }

  private void updateXmppStatus(int status) {
    int oldStatus = getConnectionStatus();
    connectionStatus = status;
    if (oldStatus != status) broadcastXmppConnectivityEvent(this);
  }

  private static void broadcastXmppConnectivityEvent(Context context) {
    Intent intent = new Intent(XMPP_CONNECTIVITY_EVENT);
    intent.setPackage(context.getPackageName());
    context.sendBroadcast(intent);
  }

  public void subscribe(String jid) {
    Presence subscribe = new Presence(Presence.Type.subscribe);
    subscribe.setTo(jid);
    try {
      connection.sendPacket(subscribe);
    } catch (Exception e) {
      Log.w(TAG, "Impossible NotConnectedException?");
    }
  }

  public Presence getRecipientPresence(String jid) {
    Roster roster = Roster.getInstanceFor(connection);
    Collection<RosterEntry> rosterEntries = roster.getEntries();

    for (RosterEntry rosterEntry : rosterEntries) {
      if (rosterEntry.getUser().equals(jid)) {
        return roster.getPresence(rosterEntry.getUser());
      }
    }
    return null;
  }

  private class XmppServiceConnectionListener implements ConnectionListener {
    private Context     context;
    private XmppService instance;

    public XmppServiceConnectionListener(XmppService instance) {
      this.context  = (Context) instance;
      this.instance = instance;
    }

    @Override
    public void authenticated(XMPPConnection connection, boolean resumed) {
      instance.updateXmppStatus(XmppService.CONNECTED);
      ServiceUtil.getNotificationManager(context).cancel(XmppService.SERVICE_RUNNING_ID);
    }

    @Override
    public void connected(XMPPConnection connection) {
      ChatManager chatManager = ChatManager.getInstanceFor(connection);
      if (chatManager.getChatListeners().size() < 1) {
        chatManager.addChatListener(new ChatManagerListener() {
          @Override
          public void chatCreated(Chat chat, boolean createdLocally) {
            if (!createdLocally) chat.addMessageListener(new XmppServiceChatMessageListener(context));
          }
        });
      }
    }

    @Override
    public void connectionClosed() {
      instance.updateXmppStatus(XmppService.DISCONNECTED);
    }

    @Override
    public void connectionClosedOnError(Exception e) {
      Log.w(TAG, e);
      instance.updateXmppStatus(XmppService.DISCONNECTED);
      instance.displayFailureNotification();
    }

    @Override
    public void reconnectingIn(int seconds) {
      Log.w(TAG, "reconnectingIn(" + seconds + ")");
    }

    @Override
    public void reconnectionFailed(Exception e) {
      instance.updateXmppStatus(XmppService.DISCONNECTED);
    }

    @Override
    public void reconnectionSuccessful() {
      instance.updateXmppStatus(XmppService.CONNECTED);
      ServiceUtil.getNotificationManager(context).cancel(XmppService.SERVICE_RUNNING_ID);
    }
  }

  private class XmppServiceChatMessageListener implements ChatMessageListener {
    private final Context context;

    public XmppServiceChatMessageListener (Context context) {
      Log.w(TAG, "Starting XMPP messages receiver...");
      this.context = context;
    }

    @Override
    public void processMessage(Chat chat, Message message){
      String senderJid = chat.getParticipant().replaceAll("\\/.*$","");

      Log.w(TAG, "Message recieved from " + senderJid);
      ArrayList<String> xmpp = new ArrayList<String>();
                        xmpp.add(senderJid);

      Recipient sender = RecipientFactory.getRecipientsFromXmpp(context, xmpp, false).getPrimaryRecipient();
      if (sender != null) {
        for (Message.Body body : message.getBodies()) {
          if (body.getMessage() != null) ApplicationContext.getInstance(context).getJobManager().add(new SmsReceiveJob(context, body.getMessage().trim(), sender.getNumber(), -1));
        }
      }
    }
  }

  private class XmppServiceDeliveryReceiptDemandListener implements PacketListener {
    XmppService instance;

    public XmppServiceDeliveryReceiptDemandListener(XmppService instance) {
      this.instance = instance;
    }

    @Override
    public void processPacket(Stanza packet) {
      if (packet instanceof Message && packet.getExtension("request", DeliveryReceipt.NAMESPACE) != null) {
        try {
          Message response = new Message(packet.getFrom().replaceAll("\\/.*$",""), Message.Type.normal);
          response.addExtension(new DeliveryReceipt(packet.getPacketID()));
          instance.connection.sendPacket(response);
        } catch (Exception e) {
          Log.w(TAG, e);
        }
      }
    }
  }

  private class XmppServiceDeliveryReceiptListener implements ReceiptReceivedListener {
    XmppService instance;

    public XmppServiceDeliveryReceiptListener (XmppService instance) {
      this.instance = instance;
    }

    @Override
    public void onReceiptReceived(String fromJid, String toJid, String deliveryReceiptId, Stanza stanza) {
      Log.d(TAG, "onReceiptReceived: from: " + fromJid + " to: " + toJid + " deliveryReceiptId: " + deliveryReceiptId + " stanza: " + stanza);
      EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(instance);
      long messageId = database.getMessageIdFromXmpp(deliveryReceiptId);
      if (messageId > -1) {
        ApplicationContext.getInstance(instance).getJobManager()
                          .add(new SmsSentJob(instance, messageId, SmsDeliveryListener.DELIVERED_SMS_ACTION, Activity.RESULT_OK));

      } else {
        Log.w(TAG, "Received delivery receipt for an unknown message");
      }
    }
  }

  private class XmppRosterListener implements RosterListener {
    private XmppService instance;

    public XmppRosterListener (XmppService instance) {
      this.instance = instance;
    }

    @Override
    public void entriesAdded(Collection<String> addresses) {}

    @Override
    public void entriesDeleted(Collection<String> addresses) {}

    @Override
    public void entriesUpdated(Collection<String> addresses) {}

    @Override
    public void presenceChanged(Presence presence) {
      instance.broadcastXmppConnectivityEvent((Context) instance);
    }
  }
}