package mx.kenzie.eris.api.event;

import mx.kenzie.eris.DiscordAPI;
import mx.kenzie.eris.api.Event;
import mx.kenzie.eris.api.entity.Guild;
import mx.kenzie.eris.api.entity.Member;

public class AddGuildMember extends Member implements Event {
    public String guild_id;
    
    public Guild getGuild() {
        if (api == null) throw DiscordAPI.unlinkedEntity(this);
        return api.getGuild(guild_id);
    }
}
