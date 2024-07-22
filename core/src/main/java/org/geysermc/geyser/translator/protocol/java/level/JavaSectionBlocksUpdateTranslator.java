/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.translator.protocol.java.level;

import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;

@Translator(packet = ClientboundSectionBlocksUpdatePacket.class)
public class JavaSectionBlocksUpdateTranslator extends PacketTranslator<ClientboundSectionBlocksUpdatePacket> {

    @Override
    public void translate(GeyserSession session, ClientboundSectionBlocksUpdatePacket packet) {
        // Send normal block updates if not many changes
        if (packet.getEntries().length < 32) {
            for (BlockChangeEntry entry : packet.getEntries()) {
                session.getWorldCache().updateServerCorrectBlockState(entry.getPosition(), entry.getBlock());
            }
            return;
        }

        UpdateSubChunkBlocksPacket subChunkBlocksPacket = new UpdateSubChunkBlocksPacket();
        subChunkBlocksPacket.setChunkX(packet.getChunkX());
        subChunkBlocksPacket.setChunkY(packet.getChunkY());
        subChunkBlocksPacket.setChunkZ(packet.getChunkZ());

        // If the entire section is updated, this might be a legacy non-full chunk update
        // which can contain thousands of unchanged blocks
        if (packet.getEntries().length == 4096 && !session.getGeyser().getWorldManager().hasOwnChunkCache()) {
            // hack - bedrock might ignore the block updates if the chunk was still loading.
            // sending an UpdateBlockPacket seems to force it
            BlockChangeEntry firstEntry = packet.getEntries()[0];
            UpdateBlockPacket blockPacket = new UpdateBlockPacket();
            blockPacket.setBlockPosition(firstEntry.getPosition());
            blockPacket.setDefinition(session.getBlockMappings().getBedrockBlock(firstEntry.getBlock()));
            blockPacket.setDataLayer(0);
            session.sendUpstreamPacket(blockPacket);

            // Filter out unchanged blocks
            Vector3i offset = Vector3i.from(packet.getChunkX() << 4, packet.getChunkY() << 4, packet.getChunkZ() << 4);
            BlockPositionIterator blockIter = BlockPositionIterator.fromMinMax(
                    offset.getX(), offset.getY(), offset.getZ(),
                    offset.getX() + 15, offset.getY() + 15, offset.getZ() + 15
            );

            int[] sectionBlocks = session.getGeyser().getWorldManager().getBlocksAt(session, blockIter);
            BitSet waterlogged = BlockRegistries.WATERLOGGED.get();
            for (BlockChangeEntry entry : packet.getEntries()) {
                Vector3i pos = entry.getPosition().sub(offset);
                int index = pos.getZ() + pos.getX() * 16 + pos.getY() * 256;
                int oldBlockState = sectionBlocks[index];
                if (oldBlockState != entry.getBlock()) {
                    // Avoid sending unnecessary waterlogged updates
                    boolean updateWaterlogged = waterlogged.get(oldBlockState) != waterlogged.get(entry.getBlock());
                    applyEntry(session, entry, subChunkBlocksPacket, updateWaterlogged);
                }
            }
        } else {
            for (BlockChangeEntry entry : packet.getEntries()) {
                applyEntry(session, entry, subChunkBlocksPacket, true);
            }
        }

        session.sendUpstreamPacket(subChunkBlocksPacket);

        // Post block update
        for (BlockChangeEntry entry : packet.getEntries()) {
            session.getWorldCache().updateServerCorrectBlockState(entry.getPosition(), entry.getBlock());
        }
    }
}
