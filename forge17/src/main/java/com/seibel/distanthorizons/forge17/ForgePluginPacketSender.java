package com.seibel.distanthorizons.forge17;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;

import com.seibel.distanthorizons.common.AbstractPluginPacketSender;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.MessageRegistry;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class ForgePluginPacketSender extends AbstractPluginPacketSender 
{
	private static BiConsumer<IServerPlayerWrapper, AbstractNetworkMessage> consumerPacket;
	
	public static final SimpleNetworkWrapper PLUGIN_CHANNEL = NetworkRegistry.INSTANCE
        .newSimpleChannel(AbstractPluginPacketSender.WRAPPER_PACKET_RESOURCE);

	
	
	//=================//
	// packet handling //
	//=================//
	//region
	
    public static void setPacketHandler(Consumer<AbstractNetworkMessage> consumer)
    { setPacketHandler((player, message) -> consumer.accept(message)); }
	
    public static void setPacketHandler(BiConsumer<IServerPlayerWrapper, AbstractNetworkMessage> consumer) 
    {
        PLUGIN_CHANNEL.registerMessage(MessageWrapper.Handler.class, MessageWrapper.class, 0, Side.CLIENT);
        PLUGIN_CHANNEL.registerMessage(MessageWrapper.Handler.class, MessageWrapper.class, 0, Side.SERVER);
        consumerPacket = consumer;
    }
	
	//endregion
	
	
	
	//=========//
	// sending //
	//=========//
	//region
	
    @Override
    public void sendToServer(AbstractNetworkMessage message) 
    { PLUGIN_CHANNEL.sendToServer(new MessageWrapper(message)); }

    @Override
    public void sendToClient(EntityPlayerMP serverPlayer, AbstractNetworkMessage message) 
    { PLUGIN_CHANNEL.sendTo(new MessageWrapper(message), serverPlayer); }
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
    // Forge doesn't support using abstract classes
    @SuppressWarnings({ "ClassCanBeRecord", "RedundantSuppression" })
    public static class MessageWrapper implements IMessage 
    {
        public AbstractNetworkMessage message;
	    
	    
	    
	    //==============//
	    // constructors //
	    //==============//
	    //region
	    
	    public MessageWrapper(AbstractNetworkMessage message) 
        { this.message = message; }

        public MessageWrapper() { /* For reflection */ }
	    
	    //endregion
	    
	    
	    
	    //===============//
	    // byte handling //
	    //===============//
	    //region
	    
        @Override
        public void fromBytes(ByteBuf buf) 
        {
            int messageId = buf.readByte();
	        this.message = MessageRegistry.INSTANCE.createMessage(messageId);
	        this.message.decode(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) 
        {
            buf.writeByte(MessageRegistry.INSTANCE.getMessageId(this.message));
	        this.message.encode(buf);
        }
	    
	    //endregion
	    
	    
	    
	    //================//
	    // helper classes //
	    //================//
	    //region
	    
	    public static class Handler implements IMessageHandler<MessageWrapper, IMessage>
	    {
		    
		    @Override
		    public IMessage onMessage(MessageWrapper wrapper, MessageContext context)
		    {
			    if (wrapper.message == null)
			    {
				    return null;
			    }
				
				
			    if (context.side == Side.SERVER)
			    {
				    consumerPacket.accept(
					    ServerPlayerWrapper.getWrapper(context.getServerHandler().playerEntity),
					    wrapper.message);
			    }
			    else
			    {
				    consumerPacket.accept(null, wrapper.message);
			    }
				
			    return null; // No response needed
		    }
		    
	    }
	    
	    //endregion
    }
	
	//endregion
	
	
	
}
