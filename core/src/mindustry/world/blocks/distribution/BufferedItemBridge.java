package mindustry.world.blocks.distribution;

import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class BufferedItemBridge extends ItemBridge{
    public final int timerAccept = timers++;

    public float speed = 40f;
    public int bufferCapacity = 50;
    public float displayedSpeed = 11f;

    public BufferedItemBridge(String name){
        super(name);
        hasPower = false;
        hasItems = true;
        canOverdrive = true;
    }
    
    @Override
    public void setStats(){
        super.setStats();

        //Hard to calculate, fps and overdive reliant. Movement speed taken from testing
        stats.add(Stat.itemsMoved, displayedSpeed, StatUnit.itemsSecond);
    }


    public class BufferedItemBridgeBuild extends ItemBridgeBuild{
        ItemBuffer buffer = new ItemBuffer(bufferCapacity);

        @Override
        public void updateTransport(Building other){
            if(buffer.accepts() && items.total() > 0){
                buffer.accept(items.take());
            }

            Item item = buffer.poll(speed / timeScale);
            if(timer(timerAccept, 4 / timeScale) && item != null && other.acceptItem(this, item)){
                moved = true;
                other.handleItem(this, item);
                buffer.remove();
            }
        }

        @Override
        public void doDump(){
            dump();
        }

        @Override
        protected void drawTransportItems(Tile other){
            super.drawTransportItems(other);
            float a = mindustry.client.morj.HiddenItems.alpha;
            if(a < 0.01f || buffer.size() <= 0) return;
            float speed = Math.max(0.01f, ((BufferedItemBridge)block).speed / timeScale);
            Draw.z(Layer.power + 0.1f);
            for(int i = 0; i < buffer.size(); i++){
                Item item = buffer.itemAt(i);
                if(item == null) continue;
                float dt = Time.time - buffer.timeAt(i);
                float progress = dt < 0f ? 1f : Mathf.clamp(dt / speed);
                Draw.color();
                Draw.alpha(a);
                Draw.rect(item.fullIcon,
                    Mathf.lerp(x, other.worldx(), progress),
                    Mathf.lerp(y, other.worldy(), progress),
                    4f, 4f);
            }
        }


        @Override
        public void write(Writes write){
            super.write(write);
            buffer.write(write);
        }


        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            buffer.read(read);
        }
    }
}