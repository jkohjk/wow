package com.jkoh.wow;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

public class BoEs {
	private static ExecutorService executors = Executors.newFixedThreadPool(2);
	private static ExecutorService printer = Executors.newSingleThreadExecutor();
	private static ObjectMapper mapper = new ObjectMapper();
	private static String auctionUrlFormat = "https://%s.api.battle.net/wow/auction/data/%s?locale=en_US&apikey=%s";
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'GMT'Z");
	private enum Region {
		US { public String subdomain() {return "us";} }, 
		EU { public String subdomain() {return "eu";} };
		public abstract String subdomain();
	}
	private static class Config {
		public String apikey;
		public Map<Region, List<List<String>>> realms;	// list of cross realm groups
		public Map<Integer, String> itemids;
		public boolean bonusRequired;
		public Map<Integer, String> bonusids_required;
		public Map<Integer, String> bonusids_display;
		public boolean modifierRequired;
		public Map<Integer, List<Integer>> modifiers_required;
		public Map<Integer, String> modifiers_display;
	}
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class Item {
		public int item;
		public long bid;
		public long buyout;
		public List<Map<String, Integer>> bonusLists;
		public List<Map<String, Integer>> modifiers;
		// unused variables do not need to be defined
	}
	public static void main(String args[]) {
		String arg = args.length > 0 ? args[0] : null;
		boolean us = arg == null || arg.equalsIgnoreCase("us");
		boolean eu = arg == null || arg.equalsIgnoreCase("eu");
		String configFilename = arg == null ? "config.json" : us ? "configus.json" : eu ? "configeu.json" : arg;
		File configFile = new File(configFilename);
		Config readConfig = null;
		if(configFile.exists()) {
			try {
				readConfig = mapper.readValue(FileUtils.readFileToString(configFile, "utf-8"), Config.class);
			} catch (Exception e) {
				System.out.println("Failed to load config file : " + configFilename);
			}
		} else if(arg == null || us || eu) {
			readConfig = generateDefaultConfig(us, eu);
			try {
				FileUtils.writeStringToFile(configFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(readConfig), "utf-8");
			} catch (Exception e) {
				System.out.println("Failed to write config file : " + configFilename);
			}
		} else {
			System.out.println("Config file not found : " + configFilename);
		}
		
		if(readConfig != null) {
			final Config config = readConfig;
			System.out.println("Started at " + dateFormat.format(new Date()));
			System.out.println();
			int countRealmGroups = 0;
			for(Region region : config.realms.keySet()) {
				countRealmGroups += config.realms.get(region).size();
			}
			final int totalRealmGroups = countRealmGroups;
			final CountDownLatch latch = new CountDownLatch(totalRealmGroups);
			final Map<Region, List<List<String>>> pendingRealms = new HashMap<>();
			for(final Region region : config.realms.keySet()) {
				pendingRealms.put(region, new ArrayList<>());
				for(final List<String> realmGroup : config.realms.get(region)) {
					pendingRealms.get(region).add(realmGroup);
					executors.submit(new Runnable() {
						public void run() {
							try {
								Map<String, Object> auctionLinkResult = httpGet(String.format(auctionUrlFormat, region.subdomain(), realmGroup.get(0), config.apikey));
								if((Integer)auctionLinkResult.get("status") == 200) {
									String auctionLink = (String)auctionLinkResult.get("content");
									Map<String, List<Map<String, String>>> auctionLinkMap = null;
									try {
										auctionLinkMap = mapper.readValue(auctionLink, HashMap.class);
										Map<String, Object> auctionDataResult = httpGet(auctionLinkMap.get("files").get(0).get("url"));
										if((Integer)auctionDataResult.get("status") == 200) {
											String auctionData = (String)auctionDataResult.get("content");
											StringBuilder auctionDataString = new StringBuilder();
											try {
												Map<String, List<Object>> auctionDataMap = mapper.readValue(auctionData, HashMap.class);
												for(Object itemObject : auctionDataMap.get("auctions")) {
													Item item = mapper.readValue(mapper.writeValueAsString(itemObject), Item.class);
													int itemID = item.item;
													if(config.itemids.containsKey(itemID)) {
														StringBuilder itemString = new StringBuilder(config.itemids.get(itemID));
														boolean bonusExist = item.bonusLists != null;
														boolean bonusMatch = false;
														boolean bonusRequirementMet = false;
														if(bonusExist) {
															List<Map<String, Integer>> bonuses = item.bonusLists;
															for(Map<String, Integer> bonus : bonuses) {
																int bonusID = (int)bonus.get("bonusListId");
																if(config.bonusids_required.containsKey(bonusID)) {
																	if(!bonusMatch) itemString.append("[");
																	else itemString.append(", ");
																	itemString.append(config.bonusids_required.get(bonusID));
																	bonusMatch = true;
																	bonusRequirementMet = true;
																} else if(config.bonusids_display.containsKey(bonusID)) {
																	if(!bonusMatch) itemString.append("[");
																	else itemString.append(", ");
																	itemString.append(config.bonusids_display.get(bonusID));
																	bonusMatch = true;
																}
															}
															if(bonusMatch) itemString.append("]");
														}
														if(!config.bonusRequired || bonusRequirementMet || (config.modifierRequired && !bonusExist)) {
															boolean modifierExist = item.modifiers != null;
															boolean modifierMatch = false;
															boolean modifierRequirementMet = false;
															if(modifierExist) {
																List<Map<String, Integer>> modifiers = item.modifiers;
																for(Map<String, Integer> modifier : modifiers) {
																	int modifierType = (int)modifier.get("type");
																	int modifierValue = (int)modifier.get("value");
																	if(config.modifiers_required.containsKey(modifierType) && config.modifiers_required.get(modifierType).contains(modifierValue)) {
																		if(!modifierMatch) itemString.append("(");
																		else itemString.append(", ");
																		if(config.modifiers_display.containsKey(modifierType)) itemString.append(config.modifiers_display.get(modifierType) + ":");
																		itemString.append(modifierValue);
																		modifierMatch = true;
																		modifierRequirementMet = true;
																	} else if(config.modifiers_display.containsKey(modifierType)) {
																		if(!modifierMatch) itemString.append("(");
																		else itemString.append(", ");
																		itemString.append(config.modifiers_display.get(modifierType) + ":");
																		itemString.append(modifierValue);
																		modifierMatch = true;
																	}
																}
																if(modifierMatch) itemString.append(")");
															}
															if(!config.bonusRequired || bonusRequirementMet || modifierRequirementMet) {
																itemString.append(" bid:" + item.bid/10000 + " buyout:" + item.buyout/10000);
																if(auctionDataString.length() > 0) auctionDataString.append("\n");
																auctionDataString.append(itemString.toString());
															}
														}
													}
												}
												print(latch.getCount(), totalRealmGroups, region, realmGroup, auctionDataString.toString());
												latch.countDown();
											} catch (Exception e) {
												e.printStackTrace();
												if(auctionDataString.length() > 0) auctionDataString.append("\n");
												print(latch.getCount(), totalRealmGroups, region, realmGroup, auctionDataString.toString() + "Error processing auction data, " + e);
												latch.countDown();
											}
										} else {
											print(latch.getCount(), totalRealmGroups, region, realmGroup, "Fail to get auction data, status " + auctionDataResult.get("status"));
											latch.countDown();
										}
									} catch (Exception e) {
										System.err.println(auctionLinkMap);
										e.printStackTrace();
										print(latch.getCount(), totalRealmGroups, region, realmGroup, "Fail to read auction link, " + e);
										latch.countDown();
									}
								} else {
									print(latch.getCount(), totalRealmGroups, region, realmGroup, "Fail to get auction link, status " + auctionLinkResult.get("status"));
									latch.countDown();
								}
							} catch (Exception e) {
								e.printStackTrace();
								print(latch.getCount(), totalRealmGroups, region, realmGroup, "Fail to get auction link, " + e);
								latch.countDown();
							} catch (Error e) {
								e.printStackTrace();
								print(latch.getCount(), totalRealmGroups, region, realmGroup, "Error, " + e);
								latch.countDown();
							}
							if(pendingRealms != null) {
								pendingRealms.get(region).remove(realmGroup);
								if(pendingRealms.get(region).isEmpty()) pendingRealms.remove(region);
							}
						}
					});
				}
			}
			try {
				latch.await(59, TimeUnit.MINUTES);
			} catch (Exception e) {}
			executors.shutdown();
			try {
				if(!executors.awaitTermination(1, TimeUnit.MINUTES)) {
					executors.shutdownNow();
				}
			} catch (Exception e) {
				executors.shutdownNow();
			}
			printer.submit(new Runnable() {
				public void run() {
					if(pendingRealms != null && !pendingRealms.isEmpty()) {
						System.out.println("Incomplete realms :");
						for(final Region region : pendingRealms.keySet()) {
							System.out.println(region + " :");
							for(final List<String> realmGroup : pendingRealms.get(region)) {
								System.out.println(realmGroup);
							}
						}
						System.out.println();
					}
					System.out.println("Finished at " + dateFormat.format(new Date()));
				}
			});
			printer.shutdown();
			try {
				if(!printer.awaitTermination(1, TimeUnit.SECONDS)) {
					printer.shutdownNow();
				}
			} catch (Exception e) {
				printer.shutdownNow();
			}
			System.exit(0);
		}
	}
	private static Map<String, Object> httpGet(String url) throws Exception {
		Map<String, Object> result = new HashMap<>();
		HttpClient httpclient = HttpClientBuilder.create().build();
		HttpResponse response = httpclient.execute(new HttpGet(url));
		int status = response.getStatusLine().getStatusCode();
		result.put("status", status);
		if (status == 200) {
			result.put("content", EntityUtils.toString(response.getEntity()));
		}
		return result;
	}
	private static void print(final long remain, final long total, final Region region, final List<String> realmGroup, final String result) {
		try{
			printer.submit(new Runnable() {
				public void run() {
					System.out.println("===== " + (total - remain + 1) + "/" + total + " " + region + ":" + realmGroup + " =====");
					System.out.println(result);
					System.out.println();
				}
			});
		} catch (Exception e) {}
	}
	private static Config generateDefaultConfig(boolean us, boolean eu) {
		Config config = new Config();
		config.apikey = "q5g39t59r2g7fe8pcxchah6aynrey9dg";
		config.realms = new LinkedHashMap<>();
		if(us) {
			config.realms.put(Region.US, Arrays.asList(
				Arrays.asList("aerie-peak"),
				Arrays.asList("agamaggan", "burning-legion", "the-underbog", "jaedenar", "archimonde"),
				Arrays.asList("aggramar", "fizzcrank"),
				Arrays.asList("alexstrasza", "terokkar"),
				Arrays.asList("alleria", "khadgar"),
				Arrays.asList("amanthul"),
				Arrays.asList("anvilmar", "undermine"),
				Arrays.asList("arathor", "drenden"),
				Arrays.asList("area-52"),
				Arrays.asList("argent-dawn", "the-scryers"),
				Arrays.asList("arthas"),
				Arrays.asList("azjolnerub", "khaz-modan"),
				Arrays.asList("azralon"),
				Arrays.asList("azshara", "destromath", "azgalor", "thunderlord"),
				Arrays.asList("baelgun", "doomhammer"),
				Arrays.asList("balnazzar", "gorgonnash", "warsong", "the-forgotten-coast", "alterac-mountains"),
				Arrays.asList("barthilas"),
				Arrays.asList("blackhand", "galakrond"),
				Arrays.asList("blackrock"),
				Arrays.asList("bladefist", "kul-tiras"),
				Arrays.asList("bleeding-hollow"),
				Arrays.asList("bloodhoof", "duskwood"),
				Arrays.asList("bronzebeard", "shandris"),
				Arrays.asList("burning-blade", "lightnings-blade", "onyxia"),
				Arrays.asList("cenarion-circle", "sisters-of-elune"),
				Arrays.asList("cenarius"),
				Arrays.asList("chogall", "laughing-skull", "auchindoun"),
				Arrays.asList("crushridge", "smolderthorn", "nathrezim", "anubarak", "chromaggus", "garithos"),
				Arrays.asList("daggerspine", "bonechewer", "gurubashi", "hakkar", "aegwynn"),
				Arrays.asList("dalaran"),
				Arrays.asList("dark-iron", "shattered-hand", "coilfang", "dalvengyr", "demon-soul"),
				Arrays.asList("darkspear"),
				Arrays.asList("detheroc", "dethecus", "shadowmoon", "haomarush", "blackwing-lair", "lethon"),
				Arrays.asList("draenor", "echo-isles"),
				Arrays.asList("dragonblight", "fenris"),
				Arrays.asList("dragonmaw", "mugthol", "akama"),
				Arrays.asList("drakkari"),
				Arrays.asList("durotan", "ysera"),
				Arrays.asList("earthen-ring"),
				Arrays.asList("eldrethalas", "korialstrasz"),
				Arrays.asList("emerald-dream"),
				Arrays.asList("eonar", "velen"),
				Arrays.asList("farstriders", "thorium-brotherhood", "silver-hand"),
				Arrays.asList("feathermoon", "scarlet-crusade"),
				Arrays.asList("frostmane", "tortheldrin", "nerzhul"),
				Arrays.asList("frostmourne"),
				Arrays.asList("gallywix"),
				Arrays.asList("garona"),
				Arrays.asList("garrosh"),
				Arrays.asList("gilneas", "elune"),
				Arrays.asList("goldrinn"),
				Arrays.asList("greymane", "tanaris"),
				Arrays.asList("guldan", "skullcrusher", "black-dragonflight"),
				Arrays.asList("gundrak", "jubeithos"),
				Arrays.asList("hellscream", "zangarmarsh"),
				Arrays.asList("hyjal"),
				Arrays.asList("illidan"),
				Arrays.asList("kaelthas", "ghostlands"),
				Arrays.asList("kalecgos", "shattered-halls", "deathwing", "executus"),
				Arrays.asList("kargath", "norgannon"),
				Arrays.asList("kelthuzad"),
				Arrays.asList("khazgoroth", "dathremar"),
				Arrays.asList("kiljaeden"),
				Arrays.asList("kirin-tor", "sentinels", "steamwheedle-cartel"),
				Arrays.asList("korgath"),
				Arrays.asList("lightbringer"),
				Arrays.asList("lightninghoof", "maelstrom", "the-venture-co"),
				Arrays.asList("llane", "arygos"),
				Arrays.asList("lothar", "grizzly-hills"),
				Arrays.asList("madoran", "dawnbringer"),
				Arrays.asList("magtheridon", "altar-of-storms", "ysondre", "anetheron"),
				Arrays.asList("malfurion", "trollbane"),
				Arrays.asList("malganis"),
				Arrays.asList("malygos", "icecrown"),
				Arrays.asList("medivh", "exodar"),
				Arrays.asList("misha", "rexxar"),
				Arrays.asList("moon-guard"),
				Arrays.asList("moonrunner", "gnomeregan"),
				Arrays.asList("nagrand", "caelestrasz"),
				Arrays.asList("nazjatar", "mannoroth", "blood-furnace"),
				Arrays.asList("nemesis"),
				Arrays.asList("nesingwary", "nazgrel", "veknilash"),
				Arrays.asList("nordrassil", "muradin"),
				Arrays.asList("perenolde", "cairne"),
				Arrays.asList("proudmoore"),
				Arrays.asList("queldorei", "senjin"),
				Arrays.asList("quelthalas"),
				Arrays.asList("ragnaros"),
				Arrays.asList("ravencrest", "uldaman"),
				Arrays.asList("sargeras"),
				Arrays.asList("saurfang"),
				Arrays.asList("shadow-council", "blackwater-raiders"),
				Arrays.asList("shadowsong", "borean-tundra"),
				Arrays.asList("shuhalo", "eitrigg"),
				Arrays.asList("silvermoon", "moknathal"),
				Arrays.asList("skywall", "drakthul"),
				Arrays.asList("staghelm", "azuremyst"),
				Arrays.asList("stonemaul", "boulderfist", "bloodscalp", "dunemaul", "maiev"),
				Arrays.asList("stormrage"),
				Arrays.asList("stormreaver"),
				Arrays.asList("stormscale", "spirestone", "firetree", "rivendare", "draktharon", "malorne"),
				Arrays.asList("suramar", "draka"),
				Arrays.asList("terenas", "hydraxis"),
				Arrays.asList("thaurissan", "dreadmaul"),
				Arrays.asList("thrall"),
				Arrays.asList("thunderhorn", "blades-edge"),
				Arrays.asList("tichondrius"),
				Arrays.asList("tol-barad"),
				Arrays.asList("turalyon"),
				Arrays.asList("twisting-nether", "ravenholdt"),
				Arrays.asList("uldum", "antonidas"),
				Arrays.asList("ursin", "scilla", "andorhal", "zuluhed"),
				Arrays.asList("uther", "runetotem"),
				Arrays.asList("vashj", "frostwolf"),
				Arrays.asList("whisperwind", "dentarg"),
				Arrays.asList("wildhammer", "spinebreaker", "eredar", "gorefiend"),
				Arrays.asList("windrunner", "darrowmere"),
				Arrays.asList("winterhoof", "kilrogg"),
				Arrays.asList("wyrmrest-accord"),
				Arrays.asList("zuljin")
			));
		}
		if(eu) {
			config.realms.put(Region.EU, Arrays.asList(
				Arrays.asList("aegwynn"),
				Arrays.asList("agamaggan","crushridge","bloodscalp","twilights-hammer","hakkar","emeriss"),
				Arrays.asList("aggra-portugues","grim-batol"),
				Arrays.asList("aggramar","hellscream"),
				Arrays.asList("alakir","xavius","skullcrusher"),
				Arrays.asList("alexstrasza","nethersturm"),
				Arrays.asList("amanthul"),
				Arrays.asList("anetheron","nathrezim","guldan","kiljaeden","festung-der-sturme","rajaxx"),
				Arrays.asList("antonidas"),
				Arrays.asList("arathor","hellfire"),
				Arrays.asList("archimonde"),
				Arrays.asList("area-52","ungoro","senjin"),
				Arrays.asList("argent-dawn"),
				Arrays.asList("arthas","kelthuzad","wrathbringer","blutkessel","veklor"),
				Arrays.asList("ashenvale"),
				Arrays.asList("aszune","shadowsong"),
				Arrays.asList("azjolnerub","quelthalas"),
				Arrays.asList("azshara","kragjin"),
				Arrays.asList("azuregos"),
				Arrays.asList("azuremyst","stormrage"),
				Arrays.asList("blackhand"),
				Arrays.asList("blackmoore"),
				Arrays.asList("blackrock"),
				Arrays.asList("blackscar"),
				Arrays.asList("bladefist","zenedar","frostwhisper"),
				Arrays.asList("blades-edge","veknilash","eonar"),
				Arrays.asList("bloodhoof","khadgar"),
				Arrays.asList("booty-bay","deathweaver"),
				Arrays.asList("borean-tundra"),
				Arrays.asList("bronze-dragonflight","nordrassil"),
				Arrays.asList("bronzebeard","aerie-peak"),
				Arrays.asList("burning-blade","drakthul"),
				Arrays.asList("burning-legion"),
				Arrays.asList("chamber-of-aspects"),
				Arrays.asList("colinas-pardas","los-errantes","tyrande"),
				Arrays.asList("confrerie-du-thorium","les-sentinelles","les-clairvoyants"),
				Arrays.asList("cthun"),
				Arrays.asList("daggerspine","sunstrider","trollbane","ahnqiraj","talnivarr","chromaggus","laughing-skull","balnazzar","shattered-halls","boulderfist"),
				Arrays.asList("dalaran","marecage-de-zangar"),
				Arrays.asList("darksorrow","neptulon","genjuros"),
				Arrays.asList("deathguard"),
				Arrays.asList("deathwing","lightnings-blade","the-maelstrom","karazhan"),
				Arrays.asList("dentarg","tarren-mill"),
				Arrays.asList("der-mithrilorden","der-rat-von-dalaran"),
				Arrays.asList("dethecus","terrordar","onyxia","theradras","mugthol"),
				Arrays.asList("die-aldor"),
				Arrays.asList("die-arguswacht","die-todeskrallen","kult-der-verdammten","das-syndikat","das-konsortium","der-abyssische-rat"),
				Arrays.asList("die-ewige-wacht","die-silberne-hand"),
				Arrays.asList("doomhammer","turalyon"),
				Arrays.asList("draenor"),
				Arrays.asList("dragonblight","ghostlands"),
				Arrays.asList("dragonmaw","stormreaver","spinebreaker","haomarush","vashj"),
				Arrays.asList("dun-modr"),
				Arrays.asList("dunemaul","auchindoun","jaedenar"),
				Arrays.asList("durotan","tirion"),
				Arrays.asList("earthen-ring","darkmoon-faire"),
				Arrays.asList("eitrigg","krasus"),
				Arrays.asList("elune","varimathras"),
				Arrays.asList("emerald-dream","terenas"),
				Arrays.asList("eredar"),
				Arrays.asList("eversong"),
				Arrays.asList("executus","shattered-hand","burning-steppes","korgall","bloodfeather"),
				Arrays.asList("fordragon"),
				Arrays.asList("forscherliga","die-nachtwache"),
				Arrays.asList("frostmane"),
				Arrays.asList("frostwolf"),
				Arrays.asList("galakrond"),
				Arrays.asList("gilneas","ulduar"),
				Arrays.asList("goldrinn"),
				Arrays.asList("gordunni"),
				Arrays.asList("greymane","lich-king"),
				Arrays.asList("grom","thermaplugg"),
				Arrays.asList("howling-fjord"),
				Arrays.asList("hyjal"),
				Arrays.asList("illidan","temple-noir","naxxramas","arathi"),
				Arrays.asList("kaelthas","arakarahm","throkferoth","rashgarroth"),
				Arrays.asList("kargath","ambossar"),
				Arrays.asList("kazzak"),
				Arrays.asList("khaz-modan"),
				Arrays.asList("khazgoroth","arygos"),
				Arrays.asList("kirin-tor"),
				Arrays.asList("kul-tiras","anachronos","alonsus"),
				Arrays.asList("la-croisade-ecarlate","conseil-des-ombres","culte-de-la-rive-noire"),
				Arrays.asList("lothar","baelgun"),
				Arrays.asList("magtheridon"),
				Arrays.asList("malganis","echsenkessel","taerar"),
				Arrays.asList("malygos","malfurion"),
				Arrays.asList("mazrigos","lightbringer"),
				Arrays.asList("medivh","suramar"),
				Arrays.asList("minahonda","exodar"),
				Arrays.asList("moonglade","steamwheedle-cartel","the-shatar"),
				Arrays.asList("nazjatar","frostmourne","zuluhed","anubarak","dalvengyr"),
				Arrays.asList("nefarian","gorgonnash","mannoroth","destromath","nerathor"),
				Arrays.asList("nemesis"),
				Arrays.asList("nerzhul","sargeras","garona"),
				Arrays.asList("norgannon","dun-morogh"),
				Arrays.asList("nozdormu","garrosh","shattrath"),
				Arrays.asList("outland"),
				Arrays.asList("perenolde","teldrassil"),
				Arrays.asList("pozzo-delleternita"),
				Arrays.asList("proudmoore","madmortem"),
				Arrays.asList("ragnaros"),
				Arrays.asList("ravencrest"),
				Arrays.asList("razuvious","deepholm"),
				Arrays.asList("rexxar","alleria"),
				Arrays.asList("runetotem","nagrand","kilrogg"),
				Arrays.asList("scarshield-legion","the-venture-co","defias-brotherhood","sporeggar","ravenholdt"),
				Arrays.asList("shendralar","sanguino","uldum","zuljin"),
				Arrays.asList("silvermoon"),
				Arrays.asList("sinstralis","chogall","eldrethalas"),
				Arrays.asList("soulflayer"),
				Arrays.asList("stormscale"),
				Arrays.asList("sylvanas"),
				Arrays.asList("terokkar","darkspear","saurfang"),
				Arrays.asList("thrall"),
				Arrays.asList("thunderhorn","wildhammer"),
				Arrays.asList("tichondrius","lordaeron"),
				Arrays.asList("twisting-nether"),
				Arrays.asList("uldaman","drekthar"),
				Arrays.asList("voljin","chants-eternels"),
				Arrays.asList("ysera","malorne"),
				Arrays.asList("ysondre"),
				Arrays.asList("zirkel-des-cenarius","todeswache")
			));
		}
		
		config.itemids = new LinkedHashMap<>();
		config.itemids.put(141564, "Telubis' Binding of Patience");
		config.itemids.put(141565, "Mir's Enthralling Grasp");
		config.itemids.put(141566, "Serrinne's Maleficent Habit");
		config.itemids.put(141567, "Cyno's Mantle of Sin");
		config.itemids.put(141568, "Boughs of Archdruid Van-Yali");
		config.itemids.put(141569, "Samnoh's Exceptional Leggings");
		config.itemids.put(141570, "Cainen's Preeminent Chestguard");
		config.itemids.put(141571, "Mavanah's Shifting Wristguards");
		config.itemids.put(141572, "Geta of Tay'Shute");
		config.itemids.put(141573, "Shokell's Grim Cinch");
		config.itemids.put(141574, "Ulfgor's Greaves of Bravery");
		config.itemids.put(141575, "Gorrog's Serene Gaze");
		config.itemids.put(141576, "Aethrynn's Everwarm Chestplate");
		config.itemids.put(141577, "Fists of Thane Kray-Tan");
		config.itemids.put(141578, "Claud's War-Ravaged Boots");
		config.itemids.put(141579, "Welded Hardskin Helmet");
		config.itemids.put(141580, "Vastly Oversized Ring");
		config.itemids.put(141581, "Demar's Band of Amore");
		config.itemids.put(141582, "Fran's Intractable Loop");
		config.itemids.put(141583, "Sameed's Vision Ring");
		config.itemids.put(141584, "Eyasu's Mulligan");
		config.itemids.put(141585, "Six-Feather Fan");
		config.itemids.put(141586, "Marfisi's Giant Censer");
		config.itemids.put(141587, "Queen Yh'saerie's Pendant");
		config.itemids.put(141588, "Talisman of Jaimil Lightheart");
		config.itemids.put(141589, "Treia's Handcrafted Shroud");
		config.itemids.put(141590, "Cloak of Martayl Oceanstrider");
		
		config.bonusRequired = true;
		config.bonusids_required = new LinkedHashMap<>();
		config.bonusids_required.put(1512, "Item Level 201");
		config.bonusids_required.put(1513, "Item Level 202");
		config.bonusids_required.put(1514, "Item Level 203");
		config.bonusids_required.put(1515, "Item Level 204");
		config.bonusids_required.put(1516, "Item Level 205");
		config.bonusids_required.put(1517, "Item Level 206");
		config.bonusids_required.put(1518, "Item Level 207");
		config.bonusids_required.put(1519, "Item Level 208");
		config.bonusids_required.put(1520, "Item Level 209");
		config.bonusids_required.put(1521, "Item Level 210");
		config.bonusids_required.put(1522, "Item Level 211");
		config.bonusids_required.put(1523, "Item Level 212");
		config.bonusids_required.put(1524, "Item Level 213");
		config.bonusids_required.put(1525, "Item Level 214");
		config.bonusids_required.put(1526, "Item Level 215");
		config.bonusids_required.put(1527, "Item Level 216");
		config.bonusids_required.put(1528, "Item Level 217");
		config.bonusids_required.put(1529, "Item Level 218");
		config.bonusids_required.put(1530, "Item Level 219");
		config.bonusids_required.put(1531, "Item Level 220");
		config.bonusids_required.put(1532, "Item Level 221");
		config.bonusids_required.put(1533, "Item Level 222");
		config.bonusids_required.put(1534, "Item Level 223");
		config.bonusids_required.put(1535, "Item Level 224");
		config.bonusids_required.put(1536, "Item Level 225");
		config.bonusids_required.put(1537, "Item Level 226");
		config.bonusids_required.put(1538, "Item Level 227");
		config.bonusids_required.put(1539, "Item Level 228");
		config.bonusids_required.put(1540, "Item Level 229");
		config.bonusids_required.put(1541, "Item Level 230");
		config.bonusids_required.put(1542, "Item Level 231");
		config.bonusids_required.put(1543, "Item Level 232");
		config.bonusids_required.put(1544, "Item Level 233");
		config.bonusids_required.put(1545, "Item Level 234");
		config.bonusids_required.put(1546, "Item Level 235");
		config.bonusids_required.put(1547, "Item Level 236");
		config.bonusids_required.put(1548, "Item Level 237");
		config.bonusids_required.put(1549, "Item Level 238");
		config.bonusids_required.put(1550, "Item Level 239");
		config.bonusids_required.put(1551, "Item Level 240");
		config.bonusids_required.put(1552, "Item Level 241");
		config.bonusids_required.put(1553, "Item Level 242");
		config.bonusids_required.put(1554, "Item Level 243");
		config.bonusids_required.put(1555, "Item Level 244");
		config.bonusids_required.put(1556, "Item Level 245");
		config.bonusids_required.put(1557, "Item Level 246");
		config.bonusids_required.put(1558, "Item Level 247");
		config.bonusids_required.put(1559, "Item Level 248");
		config.bonusids_required.put(1550, "Item Level 249");
		config.bonusids_required.put(1561, "Item Level 250");
		config.bonusids_required.put(1562, "Item Level 251");
		config.bonusids_display = new LinkedHashMap<>();
		config.bonusids_display.put(40, "Avoidance");
		config.bonusids_display.put(41, "Leech");
		config.bonusids_display.put(42, "Speed");
		config.bonusids_display.put(43, "Indestructible");
		config.bonusids_display.put(1808, "Socket");
		config.bonusids_display.put(3398, "Scales with level");
		
		config.modifierRequired = false;
		config.modifiers_required = new LinkedHashMap<>();
		config.modifiers_required.put(9, Arrays.asList(98, 99, 100));
		config.modifiers_display = new LinkedHashMap<>();
		config.modifiers_display.put(9, "Level");
		return config;
	}
}