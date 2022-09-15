package mx.kenzie.eris;

import mx.kenzie.argo.Json;
import mx.kenzie.argo.meta.JsonException;
import mx.kenzie.eris.api.Lazy;
import mx.kenzie.eris.api.annotation.Accept;
import mx.kenzie.eris.api.entity.Thread;
import mx.kenzie.eris.api.entity.*;
import mx.kenzie.eris.api.entity.command.Command;
import mx.kenzie.eris.api.entity.command.CreateCommand;
import mx.kenzie.eris.api.entity.guild.Ban;
import mx.kenzie.eris.api.entity.guild.ModifyMember;
import mx.kenzie.eris.api.entity.message.Attachment;
import mx.kenzie.eris.api.entity.message.UnsentMessage;
import mx.kenzie.eris.api.event.Interaction;
import mx.kenzie.eris.api.utility.LazyList;
import mx.kenzie.eris.api.utility.MultiBody;
import mx.kenzie.eris.data.outgoing.Outgoing;
import mx.kenzie.eris.error.APIException;
import mx.kenzie.eris.error.DiscordException;
import mx.kenzie.eris.network.CacheJson;
import mx.kenzie.eris.network.EntityCache;
import mx.kenzie.eris.network.NetworkController;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class DiscordAPI {
    
    private final NetworkController network;
    private final Bot bot;
    private final EntityCache cache = new EntityCache();
    private String application;
    
    DiscordAPI(NetworkController network, Bot bot) {
        this.network = network;
        this.bot = bot;
    }
    
    public static DiscordException unlinkedEntity(Entity entity) {
        return new DiscordException("The object " + entity.debugName() + " is not linked to a DiscordAPI.");
    }
    
    public CompletableFuture<?> dispatch(Outgoing payload) {
        return this.network.sendPayload(payload);
    }
    
    //<editor-fold desc="Request Helpers" defaultstate="collapsed">
    @SuppressWarnings("all")
    public CompletableFuture<InputStream> request(String type, String path, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.network.request(type, path, body, bot.headers).body();
            } catch (IOException | InterruptedException ex) {
                if (Bot.DEBUG_MODE) ex.printStackTrace();
                throw new DiscordException(ex);
            }
        });
    }
    
    @SuppressWarnings("all")
    public <Type> CompletableFuture<Type> get(String path, Map<?, ?> query, Type object) {
        if (query != null && !query.isEmpty()) {
            final List<String> parts = new ArrayList<>();
            for (final Map.Entry<?, ?> entry : query.entrySet()) parts.add(entry.getKey() + "=" + entry.getValue());
            path += "?" + String.join("&", parts);
        }
        return this.request("GET", path, null, object);
    }
    
    @SuppressWarnings("all")
    public <Type> CompletableFuture<Type> request(String type, String path, String body, Type object) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final HttpResponse<InputStream> request = this.network.request(type, path, body, bot.headers);
                return this.handle(request, object);
            } catch (IOException | InterruptedException ex) {
                throw new DiscordException("Error in request.", ex);
            }
        }).exceptionally(throwable -> {
            if (Bot.DEBUG_MODE) throwable.printStackTrace();
            if (throwable instanceof CompletionException ex) throwable = ex.getCause();
            if (object instanceof Lazy lazy) lazy.error(throwable);
            else Bot.handle(throwable);
            return object;
        });
    }
    
    protected <Type> Type handle(HttpResponse<InputStream> request, Type object) {
        final Map<String, Object> map = new HashMap<>();
        try (final Json json = new CacheJson(request.body(), cache)) {
            final boolean isMap = json.willBeMap();
            if (isMap) map.putAll(json.toMap());
            if (isMap && map.containsKey("code") && map.containsKey("message")) {
                if (Bot.DEBUG_MODE) System.err.println("Error " + map.get("code") + ": " + map);
                final APIException error = new APIException(map.get("message") + "");
                this.network.helper.mapToObject(error, APIException.class, map);
                throw error;
            }
            if (object == null) return null;
            else if (object instanceof LazyList<?> list && !isMap)
                list.update(this.network.helper, json.toList(), this);
            else if (object instanceof List list && !isMap) json.toList(list);
            else if (object instanceof Map source && isMap) source.putAll(map);
            else if (isMap) this.network.helper.mapToObject(object, object.getClass(), map);
            return object;
        } catch (JsonException ignored) {
            return object;
        }
    }
    
    //<editor-fold desc="Messages" defaultstate="collapsed">
    public Message write(String content) {
        final Message message = new Message();
        message.api = this;
        message.finish();
        return message;
    }
    
    public Message sendMessage(Channel channel, Message message) {
        if (channel.api == null) channel.api = this;
        return this.sendMessage(channel.id, message);
    }
    
    public Message sendMessage(String channel, Message message) {
        message.unready();
        if (message.attachments != null && message.attachments.length > 0) {
            final MultiBody body = new MultiBody();
            body.sectionMessage(Json.toJson(message, UnsentMessage.class, null));
            for (final Attachment attachment : message.attachments) {
                if (attachment.filename != null)
                    body.section("files[" + attachment.id + "]", attachment.filename, attachment.content);
                else
                    body.section("files[" + attachment.id + "]", attachment.content.toString());
            }
            body.finish();
            this.multiRequest("POST", "/channels/" + channel + "/messages", body, message)
                .exceptionally(message::error).thenAccept(Lazy::finish);
        } else {
            final String body = Json.toJson(message, UnsentMessage.class, null);
            this.post("/channels/" + channel + "/messages", body, message)
                .exceptionally(message::error).thenAccept(Lazy::finish);
        }
        if (message.api == null) message.api = this;
        return message;
    }
    
    @SuppressWarnings("all")
    public <Type> CompletableFuture<Type> multiRequest(String type, String path, MultiBody body, Type object) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final HttpResponse<InputStream> request = this.network.multiRequest(type, path, body, bot.headers);
                return this.handle(request, object);
            } catch (IOException | InterruptedException ex) {
                if (Bot.DEBUG_MODE) ex.printStackTrace();
                throw new DiscordException("Error in request.", ex);
            } finally {
                try {
                    body.close();
                } catch (Throwable ex) {
                    throw new DiscordException("Error while closing resources.", ex);
                }
            }
        }).exceptionally(throwable -> {
            if (Bot.DEBUG_MODE) throwable.printStackTrace();
            if (throwable instanceof CompletionException ex) throwable = ex.getCause();
            if (object instanceof Lazy lazy) lazy.error(throwable);
            else Bot.handle(throwable);
            return object;
        });
    }
    //</editor-fold>
    
    @SuppressWarnings("all")
    public <Type> CompletableFuture<Type> post(String path, String body, Type object) {
        return this.request("POST", path, body, object);
    }
    
    public Message sendMessage(long channel, Message message) {
        return this.sendMessage(Long.toString(channel), message);
    }
    
    public Message sendMessagePoint(String path, Message message, Class<?> type) {
        message.unready();
        if (message.attachments != null && message.attachments.length > 0) {
            final MultiBody body = new MultiBody();
            body.sectionMessage(Json.toJson(message, UnsentMessage.class, null));
            for (final Attachment attachment : message.attachments) {
                if (attachment.filename != null)
                    body.section("files[" + attachment.id + "]", attachment.filename, attachment.content);
                else
                    body.section("files[" + attachment.id + "]", attachment.content.toString());
            }
            body.finish();
            this.multiRequest("POST", path, body, message)
                .exceptionally(message::error).thenAccept(Lazy::finish);
        } else {
            final String body = Json.toJson(message, type, null);
            this.post(path, body, message)
                .exceptionally(message::error).thenAccept(Lazy::finish);
        }
        if (message.api == null) message.api = this;
        return message;
    }
    
    //<editor-fold desc="Channels" defaultstate="collapsed">
    public Channel createDirectChannel(long id) {
        return this.createDirectChannel(Long.toString(id));
    }
    
    public Channel createDirectChannel(String id) {
        final Channel channel = cache.getOrUse(id, new Channel());
        channel.api = this;
        this.post("/users/@me/channels", "{\"recipient_id\":" + id + "}", channel).thenAccept(Lazy::finish);
        return channel;
    }
    //</editor-fold>
    
    public Channel getChannel(long id) {
        return this.getChannel(Long.toString(id));
    }
    
    public Channel getChannel(String id) {
        final Channel channel = cache.getOrUse(id, new Channel());
        channel.api = this;
        channel.id = id;
        cache.store(channel);
        this.get("/channels/" + id, channel).thenAccept(Lazy::finish);
        return channel;
    }
    
    @SuppressWarnings("all")
    public <Type> CompletableFuture<Type> get(String path, Type object) {
        return this.request("GET", path, null, object);
    }
    
    public User getUser(long id) {
        return this.getUser(Long.toString(id));
    }
    //</editor-fold>
    
    public User getUser(String id) {
        final User user = cache.getOrUse(id, new User());
        user.api = this;
        user.id = id;
        this.get("/users/" + id, user).thenAccept(Lazy::finish);
        return user;
    }
    
    public void update(User user) {
        user.unready();
        cache.store(user);
        user.api = this;
        if (user instanceof Self self) this.get("/users/@me", self).thenAccept(Lazy::finish);
        else this.get("/users/" + user.id, user).thenAccept(Lazy::finish);
    }
    
    //<editor-fold desc="Guilds" defaultstate="collapsed">
    public Guild getGuild(long id) {
        return this.getGuild(Long.toString(id));
    }
    
    public Guild getGuild(String id) {
        final Guild guild = cache.getOrUse(id, new Guild());
        guild.id = id;
        guild.api = this;
        this.get("/guilds/" + id, guild).thenAccept(Lazy::finish);
        return guild;
    }
    //</editor-fold>
    
    public Guild.Preview getGuildPreview(long id) {
        return this.getGuildPreview(Long.toString(id));
    }
    
    public Guild.Preview getGuildPreview(String id) {
        final Guild.Preview preview = new Guild.Preview();
        preview.id = id;
        preview.api = this;
        this.get("/guilds/" + id + "/preview", preview).thenAccept(Lazy::finish);
        return preview;
    }
    
    public LazyList<Channel> getChannels(Guild guild) {
        final List<Object> data = new ArrayList<>();
        final List<Channel> backer = new ArrayList<>();
        final LazyList<Channel> channels = new LazyList<>(Channel.class, backer);
        guild.api = this;
        this.get("/guilds/" + guild.id + "/channels", data).thenAccept(list -> {
            final Json.JsonHelper helper = bot.network.helper;
            for (final Object o : list) {
                final Channel channel = new Channel();
                backer.add(channel);
                channel.api = this;
                helper.mapToObject(channel, Channel.class, (Map<?, ?>) o);
            }
            channels.finish();
        });
        return channels;
    }
    
    public <IGuild> LazyList<Role> getRoles(IGuild guild) {
        final LazyList<Role> roles = new LazyList<>(Role.class, new ArrayList<>());
        if (guild instanceof Guild g) g.api = this;
        this.get("/guilds/" + guild + "/roles", roles);
        return roles;
    }
    
    public <IGuild> LazyList<Role> updateRoles(IGuild guild, LazyList<Role> roles) {
        roles.unready();
        this.get("/guilds/" + guild + "/roles", roles);
        return roles;
    }
    
    //<editor-fold desc="Members" defaultstate="collapsed">
    public <IGuild, IUser> Member getMember(IGuild guild, IUser user) {
        final Member member = new Member(); // don't cache members due to the ID overload
        if (user instanceof User u) member.user = u;
        else member.user.id = this.getUserId(user);
        member.guild_id = this.getGuildId(guild);
        member.api = this;
        this.get("/guilds/" + member.guild_id + "/members/" + member.user.id, member)
            .exceptionally(member::error).thenAccept(Lazy::finish);
        return member;
    }
    
    //<editor-fold desc="Helpers" defaultstate="collapsed">
    public String getUserId(Object object) {
        if (object == null) return null;
        if (object instanceof String value) return value;
        if (object instanceof User value) return value.id;
        if (object instanceof Member value) return value.user.id;
        return Objects.toString(object);
    }
    
    public String getGuildId(Object object) {
        if (object == null) return null;
        if (object instanceof String value) return value;
        if (object instanceof Guild value) return value.id;
        if (object instanceof Guild.Preview value) return value.id;
        return Objects.toString(object);
    }
    
    public <IGuild, IUser> Member modifyMember(IGuild guild, IUser user, ModifyMember member) {
        final String gid = this.getGuildId(guild), uid = this.getUserId(user);
        final Member result;
        if (member instanceof Member value) result = value;
        else result = new Member();
        this.patch("/guilds/" + gid + "/members/" + uid, Json.toJson(member, ModifyMember.class, null), result)
            .exceptionally(result::error).thenAccept(Lazy::finish);
        return result;
    }
    
    @SuppressWarnings("all")
    public <Type> CompletableFuture<Type> patch(String path, String body, Type object) {
        return this.request("PATCH", path, body, object);
    }
    //</editor-fold>
    
    public void update(Member member) {
        if (!member.isValid()) throw new DiscordException("Unable to update member - user ID unknown.");
        if (member.guild_id == null) throw new DiscordException("Unable to update member - guild ID unknown.");
        member.unready();
        member.api = this;
        this.get("/guilds/" + member.guild_id + "/members/" + member.user.id, member)
            .exceptionally(member::error).thenAccept(Lazy::finish);
    }
    
    //<editor-fold desc="Bans" defaultstate="collapsed">
    public <IGuild, IUser> Ban getBan(IGuild guild, IUser user) {
        final String gid = this.getGuildId(guild), uid = this.getUserId(user);
        final Ban ban = new Ban();
        this.get("/guilds/" + gid + "/bans/" + uid, ban).thenAccept(Lazy::finish);
        return ban;
    }
    
    public <IGuild, IUser> void createBan(IGuild guild, IUser user, Ban ban) {
        final String gid = this.getGuildId(guild), uid = this.getUserId(user);
        this.request("PUT", "/guilds/" + gid + "/bans/" + uid, Json.toJson(ban), null).thenRun(ban::finish);
    }
    
    
    //</editor-fold>
    
    public <
        @Accept({long.class, String.class, Guild.class}) IGuild,
        @Accept({long.class, String.class, User.class}) IUser
        > void removeBan(IGuild guild, IUser user) {
        final String gid = this.getGuildId(guild), uid = this.getUserId(user);
        this.request("DELETE", "/guilds/" + gid + "/bans/" + uid, null, null);
    }
    //</editor-fold>
    
    public void update(Guild guild) {
        guild.unready();
        this.cache.store(guild);
        guild.api = this;
        this.get("/guilds/" + guild.id, guild)
            .exceptionally(guild::error).thenAccept(Lazy::finish);
    }
    
    //<editor-fold desc="Interactions" defaultstate="collapsed">
    public Command registerCommand(Command command) {
        return this.registerCommand(command, (String) null);
    }
    
    public <IGuild> Command registerCommand(Command command, IGuild guild) {
        final String id = bot.self.id;
        final String body = Json.toJson(command, CreateCommand.class, null);
        command.api = this;
        command.unready();
        if (guild == null) this.post("/applications/" + id + "/commands", body, command)
            .exceptionally(command::error).thenAccept(Lazy::finish);
        else this.post("/applications/" + id + "/guilds/" + this.getGuildId(guild) + "/commands", body, command)
            .exceptionally(command::error).thenAccept(Lazy::finish);
        return command;
    }
    
    public void deleteCommand(Command command) {
        this.deleteCommand(command.id, command.guild_id);
    }
    
    public <ICommand, IGuild> void deleteCommand(ICommand command, IGuild guild) {
        final String id = bot.self.id;
        if (guild == null) this.delete("/applications/" + id + "/commands/" + command);
        else this.delete("/applications/" + id + "/guilds/" + guild + "/commands/" + command);
    }
    
    public CompletableFuture<Void> delete(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.network.delete(path, bot.headers).body();
            } catch (IOException | InterruptedException ex) {
                throw new DiscordException(ex);
            }
        });
    }
    //</editor-fold>
    
    public <IGuild> LazyList<Command> getCommands(IGuild guild) {
        final String id = this.getGuildId(guild), self = this.getSelf().id;
        final LazyList<Command> commands = new LazyList<>(Command.class, new ArrayList<>());
        final CompletableFuture<LazyList<Command>> future;
        if (id == null) future = this.get("/applications/" + self + "/commands", commands);
        else future = this.get("/applications/" + self + "/guilds/" + id + "/commands", commands);
        future.exceptionally(commands::error).thenAccept(Lazy::finish);
        return commands;
    }
    
    //<editor-fold desc="Users" defaultstate="collapsed">
    public Self getSelf() {
        assert bot.session != null : "Bot has not connected";
        return bot.self;
    }
    
    public void interactionResponse(Interaction interaction, Interaction.Response response) {
        final String body = Json.toJson(response);
        if (response.data() instanceof Lazy lazy)
            this.post("/interactions/" + interaction.id + "/" + interaction.token + "/callback", body, lazy)
                .exceptionally(lazy::error).thenAccept(Lazy::finish);
        else this.post("/interactions/" + interaction.id + "/" + interaction.token + "/callback", body, null);
    }
    
    @SuppressWarnings("unchecked")
    public <Type> Type getLocal(String id, Class<Type> expected) {
        final Snowflake snowflake = cache.get(id);
        if (expected.isInstance(snowflake)) return (Type) snowflake;
        else return null;
    }
    
    @SuppressWarnings("unchecked")
    public <Type extends Entity> Type makeEntity(Type template, Map<String, Object> data) {
        this.cache.helper.mapToObject(template, template.getClass(), data);
        if (!(template instanceof Snowflake snowflake)) return template;
        if (!this.shouldCache(template)) return template;
        final Snowflake cached = cache.get(snowflake.id);
        if (cached != null) {
            this.cache.helper.mapToObject(cached, cached.getClass(), data);
            return (Type) cached;
        }
        this.cache.store(snowflake);
        return template;
    }
    
    protected boolean shouldCache(Object entity) {
        return (entity instanceof User || entity instanceof Guild || entity instanceof Channel);
    }
    
    @SuppressWarnings("unchecked")
    public <Type extends Entity> Type makeEntity(Type template) {
        if (!(template instanceof Snowflake snowflake)) return template;
        if (!this.shouldCache(template)) return template;
        final Snowflake cached = cache.get(snowflake.id);
        if (cached != null) return (Type) cached;
        this.cache.store(snowflake);
        return template;
        
    }
    
    @SuppressWarnings("unchecked")
    public <Type> Type getRemote(String id, Class<Type> expected) {
        if (expected == Guild.class) return (Type) this.getGuild(id);
        if (expected == Channel.class) return (Type) this.getChannel(id);
        if (expected == User.class) return (Type) this.getUser(id);
        if (expected == Guild.Preview.class) return (Type) this.getGuildPreview(id);
        else return null;
    }
    
    public void cleanCache() {
        this.cache.clean();
    }
    
    public String getApplicationID() {
        if (application == null) {
            bot.await();
            this.application = bot.self.id;
        }
        return application;
    }
    
    public <IGuild> LazyList<Thread> getActiveThreads(IGuild guild) {
        final String id = this.getGuildId(guild);
        final LazyList<Thread> threads = new LazyList<>(Thread.class, new ArrayList<>());
        final CompletableFuture<LazyList<Thread>> future;
        future = this.get("/guilds/" + id + "/threads/active", threads);
        future.exceptionally(threads::error).thenAccept(Lazy::finish);
        return threads;
    }
    //</editor-fold>
    
}
