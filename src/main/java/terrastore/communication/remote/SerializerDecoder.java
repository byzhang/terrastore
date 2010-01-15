/**
 * Copyright 2009 - 2010 Sergio Bossa (sergio.bossa@gmail.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package terrastore.communication.remote;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;
import terrastore.communication.remote.serialization.Serializer;

/**
 * @param <T>
 * @author Sergio Bossa
 */
@ChannelPipelineCoverage("all")
public class SerializerDecoder<T> extends OneToOneDecoder {

    private final Serializer<T> serializer;

    public SerializerDecoder(Serializer<T> serializer) {
        this.serializer = serializer;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, Object msg) throws Exception {
        ChannelBuffer buffer = (ChannelBuffer) msg;
        return serializer.deserialize(new ChannelBufferInputStream(buffer));
    }
}