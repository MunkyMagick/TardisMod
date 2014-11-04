package tardis.common.tileents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import appeng.api.DimentionalCoord;
import tardis.TardisMod;
import tardis.api.IActivatable;
import tardis.api.IChunkLoader;
import tardis.api.TardisFunction;
import tardis.api.TardisUpgradeMode;
import tardis.common.core.Helper;
import tardis.common.core.TardisConfigFile;
import tardis.common.core.TardisOutput;
import tardis.common.core.store.SimpleCoordStore;
import tardis.common.items.TardisKeyItem;
import tardis.common.tileents.components.TardisTEComponent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFire;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatMessageComponent;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

public class TardisCoreTileEntity extends TardisAbstractTileEntity implements IActivatable, IChunkLoader
{
	private static TardisConfigFile config;
	public static final String cannotModifyMessage	= "[TARDIS] You do not have permission to modify this TARDIS";
	public static final String cannotUpgradeMessage	= "[TARDIS] You do not have enough upgrade points";
	private int exteriorWorld;
	private int exteriorX;
	private int exteriorY;
	private int exteriorZ;
	private float lastProximity = 0;
	
	private int tickCount = 0;
	
	private double speed = 4;
	private static double maxSpeed;
	
	private int energy;
	private boolean inFlight = false;
	private int flightTimer = 0;
	private int inFlightTimer = 0;
	private int totalFlightTimer = 0;
	private int buttonTime = 80;
	
	private int timeTillTakenOff;
	private int timeTillLanding;
	private int timeTillLandingInt;
	private int timeTillLanded;
	private int numButtons;
	
	private int numRooms = 0;
	private double	tardisXP = 0;
	private int		tardisLevel = 0;
	
	private SimpleCoordStore transmatPoint = null;
	
	private int shields;
	private int hull;
	private boolean deletingRooms = false;
	private static double explodeChance = 0.25;
	private boolean explode = false;
	
	private int instability = 0;
	private int desDim = 0;
	private int desX = 0;
	private int desY = 0;
	private int desZ = 0;
	private String[] desStrs = null;
	
	private enum LockState{Open,OwnerOnly,KeyOnly,Locked};
	private LockState lockState = LockState.Open; 
	
	private HashSet<SimpleCoordStore> roomSet = new HashSet<SimpleCoordStore>();
	private String ownerName;
	private ArrayList<String> modders = new ArrayList<String>();
	
	private static ChunkCoordIntPair[] loadable = null;
	private boolean forcedFlight = false;
	
	private ArrayList<SimpleCoordStore> gridLinks = new ArrayList<SimpleCoordStore>(); 
	private int rfStored;
	private ItemStack[] items;
	private FluidStack[] fluids;
	
	private HashMap<TardisUpgradeMode,Integer> upgradeLevels = new HashMap<TardisUpgradeMode,Integer>();
	
	private static int maxNumRooms = 30;
	private static int maxNumRoomsInc = 10;
	private static int maxShields = 1000;
	private static int maxShieldsInc = 500;
	private int maxHull;
	private static int maxEnergy = 1000;
	private static int maxEnergyInc = 1000;
	private static int energyPerSecond = 1;
	private static int energyPerSecondInc = 1;

	static
	{
		config = TardisMod.configHandler.getConfigFile("tardisCore");
		explodeChance = config.getDouble("Explosion chance (on control not pressed)", 0.25);
		loadable = new ChunkCoordIntPair[4];
		loadable[0] = new ChunkCoordIntPair( 0, 0);
		loadable[1] = new ChunkCoordIntPair(-1, 0);
		loadable[2] = new ChunkCoordIntPair( 0,-1);
		loadable[3] = new ChunkCoordIntPair(-1,-1);
	}
	
	{
		maxSpeed			= config.getDouble("maxSpeed", 8);
		maxEnergy			= config.getInt("maxEnergy", 1000);
		maxNumRooms			= config.getInt("maxRooms", 30);
		maxShields	 		= config.getInt("maxShields", 1000);
		maxHull				= config.getInt("maxHull", 1000);
		maxNumRoomsInc		= config.getInt("max rooms inc per level", 10);
		maxShieldsInc		= config.getInt("max shields inc per level", 500);
		maxEnergyInc		= config.getInt("max energy inc per level", 1000);
		energyPerSecondInc	= config.getInt("energy per second inc per level",1);
		shields		= maxShields;
		hull		= maxHull;
		
		items = new ItemStack[TardisMod.numInvs];
		fluids = new FluidStack[TardisMod.numTanks];
		energy = 100;
		energyPerSecond = config.getInt("energy per second",1);
	}
	
	private void flightTick()
	{
		if(!Helper.isServer())
			return;
		if(inFlightTimer == 0)
			Helper.playSound(this, "tardismod:takeoff", 0.75F);
		totalFlightTimer++;
		
		if(inCoordinatedFlight() || forcedFlight)
			inFlightTimer++;
		if(inFlightTimer >= timeTillTakenOff)//Taken off
		{
			TardisConsoleTileEntity con = getConsole();
			int takenTime = inFlightTimer - timeTillTakenOff;
			int takenTimeTwo = flightTimer - timeTillTakenOff;
			if(!worldObj.isRemote && !forcedFlight && !con.isStable() && takenTimeTwo % buttonTime == 0 && (getButtonTime() * numButtons) >= takenTime)
			{
				if(takenTimeTwo > 0)
				{
					TardisOutput.print("TCTE", "Working out what to do!");
					if(con.unstableControlPressed())
					{
						instability -= 0.5;
						addXP(inCoordinatedFlight() ? 3 : 2);
					}
					else
					{
						instability++;
						if(shouldExplode())
							explode = true;
						sendUpdate();
					}
					instability = Helper.clamp(instability, 0, 10);
				}
				if(con != null && con.unstableFlight() && (buttonTime * numButtons) > takenTime)
					con.randomUnstableControl();
				else if((buttonTime * numButtons) <= takenTime)
					con.clearUnstableControl();
			}
			if(takenTime == 0)// remove old tardis
			{
				World w = Helper.getWorld(exteriorWorld);
				if(w != null)
				{
					if(w.getBlockId(exteriorX,exteriorY,exteriorZ) == TardisMod.tardisBlock.blockID)
					{
						w.setBlockToAir(exteriorX, exteriorY, exteriorZ);
						w.setBlockToAir(exteriorX, exteriorY+1, exteriorZ);
						TardisOutput.print("TCTE", "Blanking exterior");
						exteriorWorld = 10000;
						exteriorX = 0;
						exteriorY = 0;
						exteriorZ = 0;
					} 	
				}
			}
			if(inFlightTimer < timeTillLanding)
			{
				if(flightTimer % 69 == 0 && inFlight)
					Helper.playSound(this, "tardismod:engines", 0.75F);
				flightTimer++;
			}
			else
			{
				if(flightTimer % 69 == 0 && inFlightTimer < timeTillLandingInt)
					Helper.playSound(this, "tardismod:engines", 0.75F);
				flightTimer++;
				
				if(inFlightTimer == timeTillLanding)
					placeBox();
				
				if(inFlightTimer == timeTillLandingInt)
					Helper.playSound(this, "tardismod:landingInt", 0.75F);
				
				if(inFlightTimer >= timeTillLanded)
					land();
			}
		}
	}

	private void safetyTick()
	{
		List<Object> players = worldObj.playerEntities;
		for(Object o : players)
		{
			if(o instanceof EntityPlayer)
			{
				EntityPlayer pl = (EntityPlayer)o;
				if(pl.posY < -5 && !pl.capabilities.isFlying)
					Helper.teleportEntityToSafety(pl);
			}
		}
	}

	@Override
	public void updateEntity()
	{
		super.updateEntity();
		tickCount++;
		
		if(explode)
		{
			double xO = (rand.nextDouble()*3) - 1.5;
			double zO = (rand.nextDouble()*3) - 1.5;
			worldObj.playSound(xCoord, yCoord, zCoord, "random.explosion", 0.5F, 1, true);
			worldObj.createExplosion(null, xCoord+0.5+xO, yCoord-0.5, zCoord+0.5+zO, 1F, true);
			explode = false;
		}
		
		if(tickCount % 20 == 0)
		{
			addEnergy(getEnergyPerSecond(getLevel(TardisUpgradeMode.REGEN)),false);
			safetyTick();
		}
		
		if(inFlight)
			flightTick();
		
		if(!worldObj.isRemote)
		{
			if(tickCount % 100 == 0 && TardisMod.plReg != null)
			{
				if(!TardisMod.plReg.hasTardis(ownerName))
					TardisMod.plReg.addPlayer(ownerName, worldObj.provider.dimensionId);
			}
			
			if(deletingRooms)
			{
				Iterator<SimpleCoordStore> i = roomSet.iterator();
				if(i.hasNext())
				{
					SimpleCoordStore coord = i.next();
					TileEntity te = worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
					if(te != null && te instanceof TardisSchemaCoreTileEntity)
					{
						TardisOutput.print("TCTE", "Removing room @ " + coord);
						TardisSchemaCoreTileEntity schemaCore = (TardisSchemaCoreTileEntity)te;
						schemaCore.remove();
					}
					i.remove();
				}
				else
				{
					deletingRooms = false;
					numRooms = 0;
				}
			}
			if(tickCount % 80 == 0)
			{
				worldObj.setBlock(xCoord, yCoord+1, zCoord, TardisMod.schemaComponentBlock.blockID, 8, 3);
				sendUpdate();
			}
		}
	}

	public boolean activate(EntityPlayer player, int side)
	{
		if(!Helper.isServer())
			return true;
		sendDestinationStrings(player);
		return true;
	}
	
	public boolean hasValidExterior()
	{
		World w = Helper.getWorld(exteriorWorld);
		if(w != null)
		{
			if(w.getBlockId(exteriorX, exteriorY, exteriorZ) == TardisMod.tardisBlock.blockID)
				return true;
		}
		return false;
	}
	
	public void linkToExterior(TardisTileEntity exterior)
	{
		exteriorWorld = exterior.worldObj.provider.dimensionId;
		exteriorX = exterior.xCoord;
		exteriorY = exterior.yCoord;
		exteriorZ = exterior.zCoord;
	}
	
	public boolean hasKey(EntityPlayer player,boolean inHand)
	{
		if(inHand)
		{
			ItemStack held = player.getHeldItem();
			String on = TardisKeyItem.getOwnerName(held);
			if(on != null)
				TardisOutput.print("TCTE","Key owner = " + on);
			else
				TardisOutput.print("TCTE","Key owner = null");
			if(on != null && on.equals(ownerName))
				return true;
		}
		else
		{
			InventoryPlayer inv = player.inventory;
			if(inv == null)
				return false;
			for(ItemStack is: inv.mainInventory)
			{
				String on = TardisKeyItem.getOwnerName(is);
				if(on != null && on.equals(ownerName))
					return true;
			}
		}
		return false;
	}
	
	public boolean canOpenLock(EntityPlayer player, boolean isInside)
	{
		if(isInside && !lockState.equals(LockState.Locked))
			return true;
		else if(isInside)
			return false;
		
		if(player == null)
			return false;
		
		if(lockState.equals(LockState.Open))
			return true;
		else if(lockState.equals(LockState.Locked))
			return false;
		else if(lockState.equals(LockState.OwnerOnly))
			return player.username.equals(ownerName);
		else if(lockState.equals(LockState.KeyOnly))
			return hasKey(player,false);
		return false;
	}
	
	private void enterTardis(EntityLivingBase ent)
	{
		Helper.teleportEntity(ent, worldObj.provider.dimensionId, 13.5, 28.5, 0, 90);
	}
	
	public void enterTardis(EntityPlayer player, boolean ignoreLock)
	{
		if(player.worldObj.isRemote)
			return;
		if(ignoreLock || canOpenLock(player,false))
			enterTardis(player);
		else
			player.addChatMessage("[TARDIS]The door is locked");
	}
	
	public void leaveTardis(EntityPlayer player, boolean ignoreLock)
	{
		if(!inFlight)
		{
			if(ignoreLock || canOpenLock(player,true))
			{
				World ext = Helper.getWorld(exteriorWorld);
				if(ext != null)
				{
					if(ext.isRemote)
						return;
					int facing = ext.getBlockMetadata(exteriorX, exteriorY, exteriorZ);
					int dx = 0;
					int dz = 0;
					double rot = 0;
					switch(facing)
					{
						case 0:dz = -1;rot=180; break;
						case 1:dx =  1;rot=-90; break;
						case 2:dz =  1;rot=  0; break;
						case 3:dx = -1;rot= 90; break;
					}
					
					if(softBlock(ext,exteriorX+dx, exteriorY, exteriorZ+dz) && softBlock(ext,exteriorX+dx, exteriorY, exteriorZ+dz))
					{
						Helper.teleportEntity(player, exteriorWorld, exteriorX+0.5+(dx), exteriorY, exteriorZ+0.5+(dz),rot);
					}
					else
						player.addChatMessage("[TARDIS]The door is obstructed");
				}
				else
					player.addChatMessage("[TARDIS]The door refuses to open");
			}
			else
				player.addChatMessage("[TARDIS]The door is locked");
		}
		else
			player.addChatMessage("[TARDIS]The door won't open in flight");
	}
	
	public boolean changeLock(EntityPlayer pl,boolean inside)
	{
		if(pl.worldObj.isRemote)
			return false;
		
		TardisOutput.print("TCTE", "Changing lock");
		if(!hasKey(pl,true))
			return false;
		
		if(lockState.equals(LockState.Locked) && !inside)
			return false;
		
		int num = LockState.values().length;
		lockState = LockState.values()[((lockState.ordinal() + 1)%num)];
		if((!inside) && lockState.equals(LockState.Locked))
		{
			TardisOutput.print("TCTE", "Changing from locked because outside");
			lockState = LockState.values()[((lockState.ordinal() + 1)%num)];
		}
			
		TardisOutput.print("TTE", "Lockstate:"+lockState.toString());
		if(lockState.equals(LockState.KeyOnly))
			pl.addChatMessage("[TARDIS]The door will only open with the key");
		else if(lockState.equals(LockState.Locked))
			pl.addChatMessage("[TARDIS]The door will not open");
		else if(lockState.equals(LockState.Open))
			pl.addChatMessage("[TARDIS]The door will open for all");
		else if(lockState.equals(LockState.OwnerOnly))
			pl.addChatMessage("[TARDIS]The door will only open for its owner");
		return true;
	}
	
	public boolean takeOff(boolean forced,EntityPlayer pl)
	{
		forcedFlight = forced;
		return takeOff(pl);
	}
	
	public boolean takeOff(EntityPlayer pl)
	{
		if(!inFlight)
		{
			TardisConsoleTileEntity con = getConsole();
			int dDim = con.getDimFromControls();
			int dX = con.getXFromControls(exteriorX);
			int dY = con.getYFromControls(exteriorY);
			int dZ = con.getZFromControls(exteriorZ);
			
			int distance = Math.abs(dX - exteriorX) + Math.abs(dY - exteriorY) + Math.abs(dZ - exteriorZ) + (dDim != exteriorWorld ? 300 : 0);
			int enCost = (int) Helper.clamp(distance, 1, 500);
			if(takeEnergy(enCost,false))
			{
				instability = 0;
				inFlight = true;
				inFlightTimer = 0;
				flightTimer = 0;
				TardisTileEntity te = getExterior();
				
				timeTillTakenOff	= (20 * 11);
				timeTillLanding		= timeTillTakenOff +  (int) (((2+(2*getMaxSpeed())) - (2*getSpeed(true))) * 69);
				timeTillLandingInt	= timeTillLanding + (isFastLanding() ? 20 * 5 : 20 * 17);
				timeTillLanded		= timeTillLanding + (isFastLanding() ? 20 * 5 : 20 * 22);
				numButtons = (timeTillLanding - timeTillTakenOff) / getButtonTime();
				
				if(te != null)
					te.takeoff();
				sendUpdate();
				return true;
			}
			else if(pl != null)
				pl.addChatMessage("Not enough energy");
		}
		return false;
	}
	
	private boolean softBlock(World w, int x, int y, int z)
	{
		Block b = Helper.getBlock(w,x,y,z);
		if(b == null)
			return w.isAirBlock(x, y, z);
		return w.isAirBlock(x, y, z) || b.isBlockFoliage(w, x, y, z) || b.isBlockReplaceable(w, x, y, z) || b instanceof BlockFire;
	}
	
	private boolean isValidPos(World w, int x, int y, int z)
	{
		return y > 0 && y < 254 && softBlock(w,x,y,z) && softBlock(w,x,y+1,z);
	}
	
	private void placeBox()
	{
		if(worldObj.isRemote)
			return;
		
		TardisConsoleTileEntity con = getConsole();
		int dX = con.getXFromControls(exteriorX);
		int dY = con.getYFromControls(exteriorY);
		int dZ = con.getZFromControls(exteriorZ);
		int facing = con.getFacingFromControls();
		World w = Helper.getWorld(con.getDimFromControls());
		if(!(isValidPos(w,dX,dY,dZ)))
		{
			boolean f = false;
			int[] check = {0,1,-1,2,-2, 3,-3, 4,-4, 5,-5};
			for(int i=0;i<5&&!f;i++)
			{
				int yO = check[i];
				for(int j=0;j<5&&!f;j++)
				{
					int xO = check[j];
					for(int k=0;k<5&&!f;k++)
					{
						int zO = check[k];
						if(isValidPos(w,dX+xO,dY+yO,dZ+zO))
						{
							dX += xO;
							dY += yO;
							dZ += zO;
							f = true;
						}
					} 
				}
			}
		}
		boolean landOnGround = con.getLandFromControls();
		if(landOnGround)
		{
			int offset = 1;
			while(dY - offset > 0 && softBlock(w,dX, dY-offset, dZ))
				offset++;
			dY = dY + 1 - offset;
		}
		w.setBlock(dX, dY, dZ, TardisMod.tardisBlock.blockID, facing, 3);
		w.setBlock(dX, dY+1, dZ, TardisMod.tardisTopBlock.blockID, facing, 3);
		
		setExterior(w,dX,dY,dZ);
		TileEntity te = w.getBlockTileEntity(dX,dY,dZ);
		if(te != null && te instanceof TardisTileEntity)
		{
			TardisTileEntity tardis = (TardisTileEntity) te;
			tardis.linkedCore = this;
			tardis.linkedDimension = worldObj.provider.dimensionId;
			tardis.land(isFastLanding());
		}
	}
	
	public void land()
	{
		if(inFlight)
		{
			forcedFlight = false;
			addXP(30);
			inFlight = false;
			sendUpdate();
			worldObj.playSound(xCoord, yCoord, zCoord, "tardismod:engineDrum", 0.75F, 1, true);
			TardisTileEntity ext = getExterior();
			if(ext != null)
			{
				ext.forceLand();
				List<Entity> inside = ext.getEntitiesInside();
				for(Entity e: inside)
					if(e instanceof EntityLivingBase)
						enterTardis((EntityLivingBase) e);
			}
			TardisConsoleTileEntity con = getConsole();
			if(con != null)
				con.land();
		}
	}
	
	private int getButtonTime()
	{
		double mod = 1;
		if(getSpeed(false) != 0)
			mod = getMaxSpeed() / ((getSpeed(false)) * 2);
		else
			mod = 0;
		mod = Helper.clamp(mod, 0.5, 4);
		int buttonTimeMod = Helper.clamp((int)Math.round(buttonTime * mod),30,buttonTime*4);
		return buttonTimeMod;
	}
	
	private boolean shouldExplode()
	{
		double eC = explodeChance * ((getSpeed(false) + 1) / 3.0);
		eC *= Helper.clamp(3.0 / tardisLevel, 0.2, 1);
		return rand.nextDouble() < eC;
	}
	
	////////////////////////////////////////////////
	//////////////DATA STUFF////////////////////////
	////////////////////////////////////////////////
	
	public boolean inCoordinatedFlight()
	{
		if(inFlight)
		{
			TardisConsoleTileEntity con = getConsole();
			if(con == null)
				return true;
			return (inFlightTimer <= timeTillTakenOff) || con.shouldLand();
		}
		return false;
	}
	
	public boolean inFlight()
	{
		return inFlight;
	}
	
	public float getProximity()
	{
		if(inFlight)
		{
			int rate = 40;
			double val = Math.abs((tickCount % rate) - (rate / 2));
			double max = 0.4;
			lastProximity = (float) (max * 2 * (val / rate));
			return lastProximity;
		}
		else if(lastProximity > 0)
			return (lastProximity = lastProximity - (1/20.0F));
		else
		{
			return 0;
		}
	}
	
	public void setExterior(World w, int x, int y, int z)
	{
		exteriorWorld = w.provider.dimensionId;
		exteriorX = x;
		exteriorY = y;
		exteriorZ = z;
		TardisOutput.print("TCTE", "Exterior placed @ " + x + ","+ y +","+z+","+exteriorWorld +","+worldObj.isRemote);
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}
	
	public TardisTileEntity getExterior()
	{
		World w = Helper.getWorld(exteriorWorld);
		if(w != null)
		{
			TileEntity te = w.getBlockTileEntity(exteriorX,exteriorY,exteriorZ);
			if(te != null && te instanceof TardisTileEntity)
				return (TardisTileEntity) te;
		}
		return null;
	}
	
	public TardisConsoleTileEntity getConsole()
	{
		TileEntity te = worldObj.getBlockTileEntity(xCoord, yCoord - 2, zCoord);
		if(te instanceof TardisConsoleTileEntity)
				return (TardisConsoleTileEntity)te;
		return null;
	}
	
	public TardisEngineTileEntity getEngine()
	{
		TileEntity te = worldObj.getBlockTileEntity(xCoord, yCoord - 5, zCoord);
		if(te instanceof TardisEngineTileEntity)
			return (TardisEngineTileEntity)te;
		return null;
	}
	
	public TardisSchemaCoreTileEntity getSchemaCore()
	{
		TileEntity te = worldObj.getBlockTileEntity(xCoord, yCoord - 10, zCoord);
		if(te instanceof TardisSchemaCoreTileEntity)
			return (TardisSchemaCoreTileEntity)te;
		return null;
	}
	
	public void loadConsoleRoom(String sub)
	{
		String fullName = "tardisConsole" + sub;
		TardisSchemaCoreTileEntity schemaCore = getSchemaCore();
		if(schemaCore != null)
		{
			//schemaCore.remove();
			//Helper.loadSchema(fullName, worldObj, xCoord, yCoord - 10, zCoord, 0);
			Helper.loadSchemaDiff(schemaCore.getName(),fullName,worldObj, xCoord,yCoord-10,zCoord,0);
		}
		else
		{
			Helper.loadSchema(fullName, worldObj, xCoord, yCoord - 10, zCoord, 0);
		}
	}

	public boolean canModify(EntityPlayer player)
	{
		return canModify(player.username);
	}
	
	public boolean canModify(String playerName)
	{
		return isOwner(playerName) || modders.contains(playerName);
	}
	
	public void toggleModifier(EntityPlayer modder,String name)
	{
		if(isOwner(modder.username))
		{
			if(!modder.username.equals(name))
			{
				if(modders.contains(name))
					modders.remove(name);
				else
					modders.add(name);
			}
		}
		else
			modder.addChatMessage(cannotModifyMessage);
	}
	
	public boolean isOwner(String name)
	{
		if(ownerName != null)
			return ownerName.equals(name);
		return false;
	}
	
	public String getOwner()
	{
		return ownerName;
	}
	
	public void setOwner(String name)
	{
		TardisOutput.print("TCTE", "Setting owner to " + name+"#"+worldObj.isRemote, TardisOutput.Priority.DEBUG);
		ownerName = name;
		if(!worldObj.isRemote && TardisMod.plReg != null && !TardisMod.plReg.hasTardis(ownerName))
			TardisMod.plReg.addPlayer(ownerName, worldObj.provider.dimensionId);
	}

	public boolean hasFunction(TardisFunction fun)
	{
		switch(fun)
		{
		case LOCATE:	return tardisLevel >= 3;
		case SENSORS:	return tardisLevel >= 5;
		case STABILISE:	return tardisLevel >= 7;
		case TRANSMAT:	return tardisLevel >= 9;
		case RECALL:	return tardisLevel >= 11;
		default:		return false;
		}
	}
	
	public double getMaxSpeed()
	{
		return maxSpeed;
	}
	
	public double getSpeed(boolean modified)
	{
		if(forcedFlight)
			return getMaxSpeed();
		if(!modified)
			return speed;
		double mod = ((double)getNumRooms()) / getMaxNumRooms();
		return speed * (1-(mod / 2.0));
	}
	
	public boolean isFastLanding()
	{
		return getSpeed(true) > 8;
	}
	
	public double addSpeed(double a)
	{
		if(!inFlight)
			speed = speed + a;
		speed = Helper.clamp(speed, 0, getMaxSpeed());
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		return speed;
	}
	
	public int getLevel()
	{
		return tardisLevel;
	}
	
	public int spentLevelPoints()
	{
		int spentPoints = 0;
		for(TardisUpgradeMode mode : upgradeLevels.keySet())
			spentPoints += upgradeLevels.get(mode);
		return spentPoints;
	}
	
	public int unspentLevelPoints()
	{
		return maxUnspentLevelPoints() - spentLevelPoints();
	}
	
	public int maxUnspentLevelPoints()
	{
		return tardisLevel / 2;
	}
	
	public int getLevel(TardisUpgradeMode mode)
	{
		int level = 0;
		if(upgradeLevels.containsKey(mode))
			level = upgradeLevels.get(mode);
		return level;
	}
	
	public void upgradeLevel(TardisUpgradeMode mode, int am)
	{
		if(unspentLevelPoints() >= am)
		{
			upgradeLevels.put(mode, am + getLevel(mode));
		}
	}
	
	public double getXP()
	{
		return tardisXP;
	}
	
	public double getXPNeeded()
	{
		if(tardisLevel <= 0)
			return 1;
		return TardisMod.xpBase + (tardisLevel * TardisMod.xpInc);
	}
	
	public double addXP(double a)
	{
		tardisXP += Math.abs(a);
		if(tardisXP >= getXPNeeded())
		{
			while(tardisXP > getXPNeeded())
			{
				tardisXP -= getXPNeeded();
				tardisLevel++;
			}
			Helper.playSound(worldObj, xCoord, yCoord, zCoord, "tardismod:levelup", 1);
		}
		sendUpdate();
		return tardisXP;
	}
	
	public int getNumRooms()
	{
		return numRooms;
	}
	
	public int getMaxNumRooms(int level)
	{
		return maxNumRooms + (maxNumRoomsInc * level);
	}
	
	public int getMaxNumRooms()
	{
		return getMaxNumRooms(getLevel(TardisUpgradeMode.ROOMS));
	}
	
	public boolean addRoom(boolean sub, TardisSchemaCoreTileEntity te)
	{
		if(sub && numRooms > 0)
		{
			if(Helper.isServer() && te != null)
				roomSet.remove(new SimpleCoordStore(te));
			numRooms --;
			return true;
		}
		
		if(!sub && numRooms < maxNumRooms)
		{
			if(Helper.isServer() && te != null)
				roomSet.add(new SimpleCoordStore(te));
			numRooms++;
			return true;
		}
		
		return false;
	}
	
	public boolean addRoom(TardisSchemaCoreTileEntity te)
	{
		if(Helper.isServer() && te != null)
			return roomSet.add(new SimpleCoordStore(te));
		return false;
	}
	
	public void removeAllRooms(boolean force)
	{
		if(!force)
			removeAllRooms();
		else
		{
			for(SimpleCoordStore coord : roomSet)
			{
				TileEntity te = worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
				if(te != null && te instanceof TardisSchemaCoreTileEntity)
				{
					TardisOutput.print("TCTE", "Removing room @ " + coord);
					TardisSchemaCoreTileEntity schemaCore = (TardisSchemaCoreTileEntity)te;
					schemaCore.remove();
				}
			}
			roomSet.clear();
			numRooms = 0;
		}
	}
	
	public void removeAllRooms()
	{
		
		deletingRooms = true;
	}
	
	public int getMaxEnergy(int level)
	{
		return maxEnergy + (maxEnergyInc * level);
	}
	
	public int getMaxEnergy()
	{
		return getMaxEnergy(getLevel(TardisUpgradeMode.ENERGY));
	}
	
	public int getEnergyPerSecond(int level)
	{
		return energyPerSecond + (energyPerSecondInc * level);
	}
	
	public int getEnergy()
	{
		return energy;
	}
	
	public boolean addEnergy(int amount, boolean sim)
	{
		if(!sim)
			energy += amount;
		energy = Helper.clamp(energy,0,getMaxEnergy(getLevel(TardisUpgradeMode.ENERGY)));
		return true;
	}
	
	public boolean takeEnergy(int amount, boolean sim)
	{
		if(energy >= amount)
		{
			if(!sim)
				energy -= amount;
			return true;
		}
		energy = Helper.clamp(energy,0,getMaxEnergy(getLevel(TardisUpgradeMode.ENERGY)));
		return false;
	}
	
	public int getShields()
	{
		return shields;
	}
	
	public int getMaxShields(int level)
	{
		return maxShields + (level * maxShieldsInc);
	}
	
	public int getHull()
	{
		return hull;
	}
	
	public int getMaxHull(int level)
	{
		return maxHull;
	}
	
	public void addGridLink(SimpleCoordStore pos)
	{
		TardisOutput.print("TCTE", "Adding coord:" + pos.toString());
		if(pos != null)
			gridLinks.add(pos);
	}
	
	public DimentionalCoord[] getGridLinks(SimpleCoordStore asker)
	{
		if(gridLinks.size() == 0)
			return null;
		ArrayList<DimentionalCoord> coords = new ArrayList<DimentionalCoord>(gridLinks.size()-1);
		Iterator<SimpleCoordStore> iter = gridLinks.iterator();
		while(iter.hasNext())
		{
			SimpleCoordStore s = iter.next();
			if(s.equals(asker))
				continue;
			
			TileEntity te = worldObj.getBlockTileEntity(s.x, s.y, s.z);
			if(te instanceof TardisComponentTileEntity)
			{
				if(((TardisComponentTileEntity)te).hasComponent(TardisTEComponent.GRID))
					coords.add(new DimentionalCoord(worldObj,s.x,s.y,s.z));
				else
					iter.remove();
			}
			else
				iter.remove();
		}
		DimentionalCoord[] retVal = new DimentionalCoord[coords.size()];
		coords.toArray(retVal);
		return retVal;
	}
	
	public int getMaxRF()
	{
		return TardisMod.rfBase + Math.max((tardisLevel - 1) * TardisMod.rfInc,0);
	}
	
	public int getRF()
	{
		return rfStored;
	}
	
	public int addRF(int am, boolean sim)
	{
		int max = getMaxRF() - getRF();
		if(!sim)
			rfStored += Math.min(am, max);
		return Math.min(am,max);
	}
	
	public int remRF(int am, boolean sim)
	{
		int max = getRF();
		if(!sim)
			rfStored -= Math.min(am, max);
		return Math.min(am,max);
	}
	
	public ItemStack setIS(ItemStack is, int slot)
	{
		ItemStack ret = items[slot];
		items[slot] = is;
		return ret;
	}
	
	public ItemStack getIS(int slot)
	{
		return items[slot];
	}
	
	public int getInvSize()
	{
		return items.length;
	}
	
	public FluidStack[] getTanks()
	{
		return fluids;
	}
	
	public boolean transmatEntity(Entity ent)
	{
		if(!hasFunction(TardisFunction.TRANSMAT))
			return false;
		SimpleCoordStore to = getTransmatPoint();
		int entWorld = Helper.getWorldID(ent.worldObj);
		boolean trans = false;
		if(entWorld == Helper.getWorldID(worldObj))
			trans = true;
		else if(entWorld == exteriorWorld)
		{
			double distance	 = Math.pow(((exteriorX+0.5) - ent.posX), 2);
			distance		+= Math.pow(((exteriorY+0.5) - ent.posY), 2);
			distance		+= Math.pow(((exteriorZ+0.5) - ent.posZ), 2);
			distance		 = Math.pow(distance, 0.5);
			if(distance <= 250)
				trans = true;
		}
		if(trans)
		{
			Helper.playSound(ent.worldObj, (int) ent.posX, (int) ent.posY, (int) ent.posZ, "tardismod:transmat", 0.6F);
			Helper.teleportEntity(ent, Helper.getWorldID(worldObj), to.x+0.5, to.y+1, to.z+0.5);
			Helper.playSound(worldObj, to.x, to.y+1, to.z, "tardismod:transmat", 0.6F);
			return true;
		}
		else
		{
			Helper.playSound(ent.worldObj, (int) ent.posX, (int) ent.posY, (int) ent.posZ, "tardismod:transmatFail", 0.6F);
			return false;
		}
	}
	
	public SimpleCoordStore getTransmatPoint()
	{
		if(isTransmatPointValid())
			return transmatPoint;
		return new SimpleCoordStore(worldObj,13,28,0);
	}
	
	public void setTransmatPoint(SimpleCoordStore s)
	{
		transmatPoint = s;
	}
	
	public boolean isTransmatPoint(SimpleCoordStore other)
	{
		if(transmatPoint != null)
			return transmatPoint.equals(other);
		return false;
	}
	
	public boolean isTransmatPointValid()
	{
		if(transmatPoint == null)
			return false;
		World w= transmatPoint.getWorldObj();
		TileEntity te = w.getBlockTileEntity(transmatPoint.x,transmatPoint.y, transmatPoint.z);
		if(te != null && te instanceof TardisComponentTileEntity && ((TardisComponentTileEntity)te).hasComponent(TardisTEComponent.TRANSMAT))
			return true;
		return false;
	}
	
	public void sendDestinationStrings(EntityPlayer pl)
	{
		TardisConsoleTileEntity console = getConsole();
		if(console != null)
		{
			int dD = console.getDimFromControls();
			int dX = console.getXFromControls(exteriorX);
			int dY = console.getYFromControls(exteriorY);
			int dZ = console.getZFromControls(exteriorZ);
			TardisOutput.print("TCTE","Dest:" + dD +","+dX+","+dY+","+dZ);
			if(dD==desDim&&dX==desX&&dY==desY&&dZ==desZ&&desStrs!=null)
				for(String s:desStrs)
					pl.addChatMessage(s);
			else
			{
				int instability = Helper.clamp(20 - (2 * tardisLevel),3,20);
				desDim = dD;
				String[] send = new String[4];
				if(desStrs!= null && desStrs.length == 4)
					send = desStrs;
				
				send[0] = "The TARDIS will materialize in dimension " + dD + " near:";
				if(dX != desX || send[1] == null)
					send[1] = "x = " + (dX + (rand.nextInt(2 * instability) - instability));
				if(dY != desY || send[2] == null)
					send[2] = "y = " + (dY + (rand.nextInt(2 * instability) - instability)); 
				if(dZ != desZ || send[3] == null)
				send[3] = "z = " + (dZ + (rand.nextInt(2 * instability) - instability)); 
				desX = dX;
				desY = dY;
				desZ = dZ;
				desStrs = send;
				for(String s:desStrs)
					pl.addChatMessage(s);
			}
		}
	}
	
	private boolean[] getObstructData(World w, int x, int y, int z)
	{
		boolean[] data = new boolean[2];
		data[0] = data[1] = false;
		TardisOutput.print("TCTE", "Checking for air @ " + x + "," + y + "," + z,TardisOutput.Priority.DEBUG);
		if(softBlock(w,x, y, z) && softBlock(w, x, y+1, z))
		{
			data[0] = true;
			int id = w.getBlockId(x, y-1, z);
			if(softBlock(w,x, y-1, z) || id == Block.fire.blockID || id == Block.lavaMoving.blockID || id == Block.lavaStill.blockID || id == Block.waterMoving.blockID || id == Block.waterStill.blockID)
				data[1] = true;
		}
		return data;
	}
	
	public void sendScannerStrings(EntityPlayer pl)
	{
		if(inFlight())
		{
			pl.sendChatToPlayer(new ChatMessageComponent().addText("Cannot use temporal scanners while in flight"));
			return;
		}
		List<String> string = new ArrayList<String>();
		TardisTileEntity ext = getExterior();
		World w = ext.worldObj;
		int dx = 0;
		int dz = 0;
		string.add("Current position: Dimension " + exteriorWorld + " : " + exteriorX+","+exteriorY+","+exteriorZ);
		int facing = w.getBlockMetadata(exteriorX, exteriorY, exteriorZ);
		for(int i=0;i<4;i++)
		{
			switch(i)
			{
				case 0:dz = -1; dx = 0; break;
				case 1:dx =  1; dz = 0; break;
				case 2:dz =  1; dx = 0; break;
				case 3:dx = -1; dz = 0; break;
			}
			String s = (i == facing ? "Current facing " : "Facing ");
			boolean[] data = getObstructData(w,exteriorX+dx,exteriorY,exteriorZ+dz);
			if(!data[0])
				s += "obstructed";
			else if(!data[1])
				s += "safe";
			else
				s += "unsafe drop";
			string.add(s);
		}
		
		for(String s:string)
			pl.addChatMessage(s);
	}
	
	//////////////////////////////
	//////NBT DATA////////////////
	//////////////////////////////
	
	public void repair(String newO, int numRoom, int en)
	{
		energy = en;
		numRooms = numRoom;
		maxNumRooms = config.getInt("maxRooms", 30);
		setOwner(newO);
	}
	
	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		super.readFromNBT(nbt);
		forcedFlight = nbt.getBoolean("forcedFlight");
		lockState = LockState.values()[nbt.getInteger("lockState")];
		rfStored  = nbt.getInteger("rfStored");
		if(nbt.hasKey("transmatPoint"))
			transmatPoint = SimpleCoordStore.readFromNBT(nbt.getCompoundTag("transmatPoint"));
		if(nbt.hasKey("invStore"))
		{
			NBTTagCompound invTag = nbt.getCompoundTag("invStore");
			for(int i = 0;i<items.length;i++)
			{
				if(invTag.hasKey("i"+i))
					items[i] = ItemStack.loadItemStackFromNBT(invTag.getCompoundTag("i"+i));
			}
		}
		if(nbt.hasKey("fluidStore"))
		{
			NBTTagCompound invTag = nbt.getCompoundTag("fluidStore");
			for(int i = 0;i<fluids.length;i++)
			{
				if(invTag.hasKey("i"+i))
					fluids[i] = FluidStack.loadFluidStackFromNBT(invTag.getCompoundTag("i"+i));
			}
		}
		if(nbt.hasKey("modders"))
		{
			String[] mods	 = nbt.getString("modders").split("||");
			if(mods != null && mods.length > 0)
			{
				for(String m : mods)
					modders.add(m);
			}
		}
	}
	
	private void storeInv(NBTTagCompound nbt)
	{
		int j = 0;
		NBTTagCompound invTag = new NBTTagCompound();
		for(int i = 0;i<items.length;i++)
		{
			if(items[i] != null)
			{
				NBTTagCompound iTag = new NBTTagCompound();
				invTag.setCompoundTag("i"+j, items[i].writeToNBT(iTag));
				j++;
			}
		}
		if(j > 0)
			nbt.setCompoundTag("invStore", invTag);
	}
	
	private void storeFlu(NBTTagCompound nbt)
	{
		int j = 0;
		NBTTagCompound invTag = new NBTTagCompound();
		for(int i = 0;i<fluids.length;i++)
		{
			if(fluids[i] != null)
			{
				NBTTagCompound iTag = new NBTTagCompound();
				invTag.setCompoundTag("i"+j, fluids[i].writeToNBT(iTag));
				j++;
			}
		}
		if(j > 0)
			nbt.setCompoundTag("fluidStore", invTag);
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);
		nbt.setBoolean("forcedFlight", forcedFlight);
		nbt.setInteger("lockState", lockState.ordinal());
		nbt.setInteger("rfStored",rfStored);
		if(hasFunction(TardisFunction.TRANSMAT) && isTransmatPointValid())
			nbt.setCompoundTag("transmatPoint", transmatPoint.writeToNBT());
		storeInv(nbt);
		storeFlu(nbt);
		if(modders != null && modders.size() > 0)
		{
			String mods = modders.get(0);
			for(int i = 1;i<modders.size(); i++)
			{
				mods += "||" + modders.get(i);
			}
			nbt.setString("modders", mods);
		}
	}

	@Override
	public void writeTransmittable(NBTTagCompound nbt)
	{
		if(ownerName != null)
		{
			nbt.setDouble("tardisXP", tardisXP);
			nbt.setBoolean("explode",explode);
			nbt.setInteger("tardisLevel", tardisLevel);
			nbt.setString("ownerName", ownerName);
			nbt.setInteger("extWorld", exteriorWorld);
			nbt.setInteger("extX", exteriorX);
			nbt.setInteger("extY", exteriorY);
			nbt.setInteger("extZ", exteriorZ);
			
			nbt.setInteger("energy",energy);
			
			nbt.setBoolean("iF", inFlight);
			nbt.setInteger("fT", flightTimer);
			nbt.setInteger("tFT", totalFlightTimer);
			nbt.setInteger("iFT", inFlightTimer);
			nbt.setInteger("numR", numRooms);
			nbt.setDouble("sped", speed);
			
			nbt.setInteger("shld",shields);
			nbt.setInteger("hull",hull);
			
			
			if(inFlight())
			{
				nbt.setInteger("ttTO", timeTillTakenOff);
				nbt.setInteger("ttL" , timeTillLanding);
				nbt.setInteger("ttLI", timeTillLandingInt);
				nbt.setInteger("ttLa", timeTillLanded);
			}
			if(upgradeLevels.size() > 0)
			{
				for(TardisUpgradeMode mode : upgradeLevels.keySet())
				{
					int am = upgradeLevels.get(mode);
					if(am > 0)
						nbt.setInteger("uG" + mode.ordinal(), am);
				}
			}
		}
	}

	@Override
	public void readTransmittable(NBTTagCompound nbt)
	{
		if(nbt.hasKey("ownerName"))
		{
			tardisXP  = nbt.getDouble("tardisXP");
			explode = nbt.getBoolean("explode");
			tardisLevel = nbt.getInteger("tardisLevel");
			ownerName = nbt.getString("ownerName");
			exteriorWorld = nbt.getInteger("extWorld");
			exteriorX = nbt.getInteger("extX");
			exteriorY = nbt.getInteger("extY");
			exteriorZ = nbt.getInteger("extZ");
			
			energy = nbt.getInteger("energy");
			
			flightTimer = nbt.getInteger("fT");
			inFlightTimer = nbt.getInteger("iFT");
			totalFlightTimer = nbt.getInteger("tFT");
			inFlight = nbt.getBoolean("iF");
			numRooms = nbt.getInteger("numR");
			speed = nbt.getDouble("sped");
			
			shields  = nbt.getInteger("shld");
			hull     = nbt.getInteger("hull");
			
			if(nbt.hasKey("ttTO"))
			{
				timeTillTakenOff	= nbt.getInteger("ttTO");
				timeTillLanding		= nbt.getInteger("ttL");
				timeTillLandingInt	= nbt.getInteger("ttLI");
				timeTillLanded		= nbt.getInteger("ttLa");
			}
			
			for(TardisUpgradeMode mode :TardisUpgradeMode.values())
			{
				if(nbt.hasKey("uG"+mode.ordinal()))
				{
					int am = nbt.getInteger("uG"+mode.ordinal());
					if(am > 0)
						upgradeLevels.put(mode, am);
				}
			}
		}
	}

	@Override
	public boolean shouldChunkload()
	{
		return true;
	}

	@Override
	public SimpleCoordStore coords()
	{
		return coords;
	}

	@Override
	public ChunkCoordIntPair[] loadable()
	{
		return loadable;
	}

}