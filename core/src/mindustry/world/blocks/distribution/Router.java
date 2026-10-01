package mindustry.world.blocks.distribution;

import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.client.morj.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.meta.*;

public class Router extends Block{
    public float speed = 8f;

    public Router(String name){
        super(name);
        solid = false;
        underBullets = true;
        update = true;
        hasItems = true;
        itemCapacity = 1;
        group = BlockGroup.transportation;
        unloadable = false;
        noUpdateDisabled = true;
        // Items inside the router have to animate, so the sprite is drawn every frame.
        drawCached = false;
        drawDynamic = true;
    }

    public class RouterBuild extends Building implements ControlBlock{
        protected byte[] cycles = new byte[Vars.content.items().size];
        public Item lastItem;
        public Tile lastInput;
        public Building lastTarget;
        public float time;
        public @Nullable BlockUnitc unit;

        @Override
        public Unit unit(){
            if(unit == null){
                unit = (BlockUnitc)UnitTypes.block.create(team);
                unit.tile(this);
            }
            return (Unit)unit;
        }

        @Override
        public boolean canControl(){
            return true;
        }

        @Override
        public boolean shouldAutoTarget(){
            return false;
        }

        @Override
        public void updateTile(){
            if(lastItem == null && items.any()){
                lastItem = items.first();
            }

            if(lastItem != null){
                time += 1f / speed * delta();
                Building target = getTileTarget(lastItem, lastInput, false);
                if(target != null) lastTarget = target;

                if(target != null && (time >= 1f || !(target.block instanceof Router || target.block.instantTransfer))){
                    getTileTarget(lastItem, lastInput, true);
                    target.handleItem(this, lastItem);
                    items.remove(lastItem, 1);
                    lastItem = null;
                }
            }
        }

        @Override
        public int acceptStack(Item item, int amount, Teamc source){
            return 0;
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            return team == source.team && lastItem == null && items.total() == 0;
        }

        @Override
        public void handleItem(Building source, Item item){
            items.add(item, 1);
            lastItem = item;
            time = 0f;
            lastInput = source.tile;
        }

        @Override
        public void draw(){
            super.draw();
            float a = HiddenItems.alpha;
            if(a < 0.01f || lastItem == null) return;
            Building target = lastTarget;
            float tx = target != null && target.isValid() ? target.x : x;
            float ty = target != null && target.isValid() ? target.y : y;
            float p = Mathf.clamp(time);
            Draw.z(Layer.blockOver);
            Draw.color();
            Draw.alpha(a);
            Draw.rect(lastItem.fullIcon, Mathf.lerp(x, tx, p), Mathf.lerp(y, ty, p), 4f, 4f);
            Draw.reset();
        }

        @Override
        public int removeStack(Item item, int amount){
            int result = super.removeStack(item, amount);
            if(result != 0 && item == lastItem){
                lastItem = null;
            }
            return result;
        }

        public Building getTileTarget(Item item, Tile from, boolean set){
            if(unit != null && isControlled()){
                unit.health(health);
                unit.ammo((items.total() > 0 ? 1f : 0f));
                unit.team(team);
                unit.set(x, y);

                int angle = Mathf.mod((int)((angleTo(unit.aimX(), unit.aimY()) + 45) / 90), 4);

                if(unit.isShooting()){
                    Building other = nearby(angle);
                    if(other != null && other.acceptItem(this, item)){
                        return other;
                    }
                }

                return null;
            }

            //keep track of target offsets per-item to fix https://github.com/Anuken/Mindustry/issues/12471
            int id = item.id;
            int counter = cycles[id];
            for(int i = 0; i < proximity.size; i++){
                Building other = proximity.get((i + counter) % proximity.size);
                if(set) cycles[id] = ((byte)((cycles[id] + 1) % proximity.size));
                if(other.tile == from && from.block() == Blocks.overflowGate) continue;
                if(other.acceptItem(this, item)){
                    return other;
                }
            }
            return null;
        }
    }
}
