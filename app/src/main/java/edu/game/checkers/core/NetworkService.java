package edu.game.checkers.core;

import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.filter.PresenceTypeFilter;
import org.jivesoftware.smack.filter.StanzaTypeFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.roster.RosterListener;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.game.checkers.core.callbacks.Callback0;
import edu.game.checkers.core.callbacks.Callback1;
import edu.game.checkers.core.callbacks.Callback3;
import edu.game.checkers.core.callbacks.ConnectionCallback;


public class NetworkService extends Service {

    private final IBinder binder = new NetworkBinder();

    private final static int PORT = 5222;
    private final static String HOST = "89.40.127.125";
    private final static String SERVICE_NAME = "example.com";

    private String jid;

    private XMPP xmpp;
    private ConnectionCallback connectionCallback = null;
    private Callback1<String> gameInviteCallback = null;

    @Override
    public void onDestroy() {
        new EndConnectionTask().execute();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void connectToServer(String username, String password, ConnectionCallback callback)
    {
        this.connectionCallback = callback;
        this.jid = toJid(username);
        new ConnectToServerTask(callback).execute(username, password);
    }

    public void subscribeUser(final String username, final Callback1<Boolean> callback){
        try{
            Presence subscribe = new Presence(Presence.Type.subscribe);
            subscribe.setTo(toJid(username));
            xmpp.conn.sendStanza(subscribe);

            xmpp.conn.addAsyncStanzaListener(new StanzaListener() {
                @Override
                public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                    if(toUsername(packet.getFrom()).equals(username))
                        callback.onAction(true);
                }
            }, PresenceTypeFilter.SUBSCRIBED);
            xmpp.conn.addAsyncStanzaListener(new StanzaListener() {
                @Override
                public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                    if(toUsername(packet.getFrom()).equals(username))
                        callback.onAction(false);
                }
            }, PresenceTypeFilter.UNSUBSCRIBED);
        }
        catch(SmackException.NotConnectedException e){
            this.connectionCallback.onConnectionError(e.getMessage());
        }
    }

    public void unsubscribeUser(String username){
        try {
            RosterEntry entry = xmpp.roster.getEntry(toJid(username));
            xmpp.roster.removeEntry(entry);
        } catch(Exception e){
            connectionCallback.onConnectionError(e.getMessage());
        }
    }

//    public void startConnection(String to){
//        ChatManager manager = ChatManager.getInstanceFor(xmpp.conn);
//        manager.createChat(toJid(to));
//    }
//
//    public void listenForConnection(final Callback1<CommunicationManager> callback){
//        ChatManager chatManager = ChatManager.getInstanceFor(xmpp.conn);
//        chatManager.addChatListener(
//                new ChatManagerListener() {
//                    @Override
//                    public void chatCreated(Chat chat, boolean createdLocally) {
//                        callback.onAction(new CommunicationManager(jid,
//                                chat, connectionCallback));
//                    }
//                });
//    }

    public CommunicationManager getCommunicationManager(String to){
        return new CommunicationManager(toJid(to), xmpp.conn, connectionCallback);
    }

    public Collection<Friend> getFriendsList() {
        try{
            // reload list
            xmpp.roster.reloadAndWait();

            Collection<RosterEntry> entries = xmpp.roster.getEntries();
            Collection<Friend> users = new ArrayList<>();
            for(RosterEntry entry : entries){
                Presence presence = xmpp.roster.getPresence(entry.getUser());
                Friend friend = new Friend(toUsername(entry.getUser()), presence.getType().name(), presence.getStatus());
                friend.accepted = xmpp.roster.isSubscribedToMyPresence(entry.getUser());

                users.add(friend);
            }

            return users;
        } catch(Exception e){
            connectionCallback.onConnectionError(e.getMessage());
            return null;
        }
    }

    public void setPresenceListener(final Callback3<String, String, String> listener){
        xmpp.roster.addRosterListener(new RosterListener() {

            public void entriesAdded(Collection<String> addresses) {}
            public void entriesUpdated(Collection<String> addresses) {}
            public void entriesDeleted(Collection<String> addresses) {}

            @Override
            public void presenceChanged(Presence presence) {
                String username = toUsername(presence.getFrom());
                listener.onAction(username, presence.getType().name(), presence.getStatus());
            }
        });
    }

    public void setSubscriptionListener(final Callback0 listener){
        xmpp.subscriptionListener = listener;
    }

    public void setGameInviteCallback(Callback1<String> gameInviteCallback){
        this.gameInviteCallback = gameInviteCallback;
    }

    public void sendInvitation(final String user, final Callback1<Boolean> responseCallback){
        try{
            Message stanza = new Message();
            stanza.setTo(toJid(user));

            JSONObject json = new JSONObject();
            json.put("type", "request");
            json.put("body", "invite");

            stanza.setBody(json.toString());
            xmpp.conn.sendStanza(stanza);

            xmpp.conn.addAsyncStanzaListener(new StanzaListener() {
                @Override
                public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                    Message message = (Message) packet;

                    if(toUsername(message.getFrom()).equals(user)) {
                        try {
                            JSONObject json = new JSONObject(message.getBody());

                            String type = json.get("type").toString();
                            String body = json.get("body").toString();

                            if (type.equals("response")) {
                                responseCallback.onAction(body.equals("yes"));
                                xmpp.conn.removeAsyncStanzaListener(this);
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }, StanzaTypeFilter.MESSAGE );
        }
        catch (JSONException e){
            e.printStackTrace();
        }
        catch (SmackException.NotConnectedException e){
            connectionCallback.onConnectionError(e.getMessage());
        }
    }

    public void sendResponse(String user, String response){
        try{
            Message stanza = new Message();
            stanza.setTo(toJid(user));

            JSONObject json = new JSONObject();
            json.put("type", "response");
            json.put("body", response);

            stanza.setBody(json.toString());
            xmpp.conn.sendStanza(stanza);
        }
        catch (JSONException e){
            //TODO
        }
        catch (SmackException.NotConnectedException e){
            connectionCallback.onConnectionError(e.getMessage());
        }
    }

    private String toUsername(String jid){
        int index = jid.indexOf(SERVICE_NAME);
        return jid.substring(0, index - 1);
    }

    private String toJid(String username) {
        return username + "@" + SERVICE_NAME;
    }

    public class NetworkBinder extends Binder {
        NetworkService getService() {
            // Return this instance of NetworkService so clients can call public methods
            return NetworkService.this;
        }
    }

    /*
    * params - {username, password}
     */
    private class ConnectToServerTask extends AsyncTask<String, Void, Void> {

        XMPP handler;
        ConnectionCallback callback;
        Exception exception = null;

        public ConnectToServerTask(ConnectionCallback callback){
            this.callback = callback;
        }

        @Override
        protected Void doInBackground(String... params){
            try {
                handler = new XMPP(params[0], params[1]);
            } catch (Exception e) {
                exception = e;
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(exception != null)
                callback.onConnectionError(exception.getMessage());
            else {
                xmpp = handler;
                callback.onSuccess();
            }
        }
    }

    private class EndConnectionTask extends AsyncTask<Void, Void, Void>{

        @Override
        protected Void doInBackground(Void... params) {
            try {
                //TODO
                throw new IOException();
            }
            catch(IOException e) {
                Logger.getAnonymousLogger().log(Level.FINE, "closing socket error");
            }
            return null;
        }
    }

    private class XMPP {

        private AbstractXMPPConnection conn;
        private Roster roster;
        private Callback0 subscriptionListener = null;

        public XMPP(final String username, String password)
                throws IOException, XMPPException, SmackException {

            XMPPTCPConnectionConfiguration config = XMPPTCPConnectionConfiguration.builder()
                    .setUsernameAndPassword(username, password)
                    .setHost(HOST)
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
                    .setServiceName(SERVICE_NAME)
                    .setPort(PORT)
                    .setDebuggerEnabled(true)
                    .build();

            conn = new XMPPTCPConnection(config);

            conn.connect();
            conn.login();
            if(conn.isAuthenticated()) {
                roster = Roster.getInstanceFor(conn);
                roster.setSubscriptionMode(Roster.SubscriptionMode.manual);

                // Accepts all subscribe requests and also send subscribe request back
                conn.addAsyncStanzaListener(
                        new StanzaListener() {
                            @Override
                            public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                                Presence subscribed = new Presence(Presence.Type.subscribed);
                                subscribed.setTo(packet.getFrom());
                                conn.sendStanza(subscribed);

                                if (!roster.isLoaded()) {
                                    try {
                                        roster.reloadAndWait();
                                    } catch (Exception e) {
                                        connectionCallback.onConnectionError(e.getMessage());
                                    }
                                }

                                if (roster.getEntry(packet.getFrom()) == null) {
                                    Presence subscribe = new Presence(Presence.Type.subscribe);
                                    subscribe.setTo(packet.getFrom());
                                    conn.sendStanza(subscribe);
                                }
                            }
                        },
                        PresenceTypeFilter.SUBSCRIBE);

                conn.addAsyncStanzaListener(new StanzaListener() {
                    @Override
                    public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                        if (!roster.isLoaded()) {
                            try {
                                roster.reloadAndWait();
                            } catch (Exception e) {
                                connectionCallback.onConnectionError(e.getMessage());
                                return;
                            }
                        }
                        RosterEntry entry = roster.getEntry(packet.getFrom());
                        if(entry != null) {
                            try {
                                roster.removeEntry(entry);
                                if(subscriptionListener != null)
                                    subscriptionListener.onAction();
                            } catch (Exception e) {
                                connectionCallback.onConnectionError(e.getMessage());
                            }
                        }
                    }
                }, PresenceTypeFilter.UNSUBSCRIBE);

                conn.addAsyncStanzaListener(new StanzaListener() {
                    @Override
                    public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                        if(subscriptionListener != null)
                            subscriptionListener.onAction();
                    }
                }, PresenceTypeFilter.SUBSCRIBED);

                conn.addAsyncStanzaListener(new StanzaListener() {
                    @Override
                    public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
                        Message message = (Message) packet;

                        try{
                            JSONObject json = new JSONObject(message.getBody());

                            String type = json.get("type").toString();
                            String body = json.get("body").toString();

                            if(type.equals("request") && body.equals("invite")){
                                gameInviteCallback.onAction(toUsername(message.getFrom()));
                            }
                        }
                        catch (JSONException e){
                            //TODO
                        }
                    }
                }, StanzaTypeFilter.MESSAGE);
            }
        }
    }
}