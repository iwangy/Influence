package com.coolioasjulio.influence.web;

import com.coolioasjulio.influence.Action;
import com.coolioasjulio.influence.Card;
import com.coolioasjulio.influence.Game;
import com.coolioasjulio.influence.Player;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

public class WebGame extends Game {
    private final Map<Player, PlayerEndpoint> endpointMap;
    private final Map<String, List<String>> cachedJson;
    private final Object endpointMapLock = new Object();
    private final ExecutorService executorService;
    private final Gson gson;
    private final Lobby lobby;

    public WebGame(Lobby lobby, PlayerEndpoint[] endpoints) {
        // Map the endpoints to their names and populate the player array
        super(Arrays.stream(endpoints).map(PlayerEndpoint::getName).toArray(String[]::new));
        this.lobby = lobby;
        // Create and populate a map from player objects to endpoints, so we can access the communication
        endpointMap = Collections.synchronizedMap(new HashMap<>());
        for (int i = 0; i < players.length; i++) {
            endpointMap.put(players[i], endpoints[i]);
        }

        gson = new Gson();
        cachedJson = Collections.synchronizedMap(new HashMap<>());
        executorService = Executors.newFixedThreadPool(8);
    }

    public void playerReconnected(PlayerEndpoint newEndpoint) {
        String name = newEndpoint.getName();
        synchronized (endpointMapLock) {
            // Iterate through all the players to find the player to replace
            for (Map.Entry<Player, PlayerEndpoint> entry : endpointMap.entrySet()) {
                Player player = entry.getKey();
                // Make sure the player was disconnected, and has the same name
                if (!entry.getValue().isConnected() && name.equals(player.getName())) {
                    endpointMap.put(player, newEndpoint); // overwrite the old player with the new one

                    // Send an update message so the client can populate the game information
                    Message message = new Message("update");
                    message.content = gson.toJsonTree(Arrays.stream(players).filter(Objects::nonNull).toArray(Player[]::new));
                    String updateJson = gson.toJson(message);
                    newEndpoint.write(updateJson);

                    // This is a list of messages that the player missed while disconnected
                    List<String> jsonList = cachedJson.get(player.getName());
                    // If the list exists, send all the stored messages
                    if (jsonList != null) {
                        boolean success = true;
                        // Iterate through the list and send the messages
                        while (!jsonList.isEmpty()) {
                            // Remove messages as we send them
                            String json = jsonList.remove(0);
                            success = newEndpoint.write(json);
                            // If we failed to send this message, stop reconnecting
                            if (!success) {
                                // Add back the message that didn't send
                                jsonList.add(0, json);
                                break;
                            }
                        }
                    }
                    // Inform all players that this player reconnected
                    log("%s has reconnected to the game!", player.getName());
                    return;
                }
            }
        }

        throw new IllegalArgumentException("This player has not disconnected, or is not in the game!");
    }

    /**
     * Called when a player has disconnected from the game.
     *
     * @param player The player that disconnected.
     */
    public void onPlayerDisconnected(PlayerEndpoint player) {
        // Report to other players that this player disconnected
        log("%s disconnected from the game! Waiting for them to reconnect...", player.getName());
    }

    /**
     * Broadcast a message to all connected players.
     *
     * @param type    The message type
     * @param content The content of the message. It will be serialized as JSON.
     */
    private void broadcast(String type, Object content) {
        // Create a new message
        Message message = new Message(type);
        // Serialize the content and the message
        message.content = gson.toJsonTree(content);
        String json = gson.toJson(message);
        // Broadcast it to every connected player
        synchronized (endpointMapLock) {
            for (PlayerEndpoint endpoint : endpointMap.values()) {
                write(endpoint, json);
            }
        }
    }

    /**
     * Send a JSON string to the specified player endpoint.
     * This also caches the sent message if the write doesn't succeed, so it may be retransmitted later.
     * Regardless if the write succeeds, an entry will be made in <code>cachedJson</code>, creating a new list.
     *
     * @param endpoint The endpoint to send to
     * @param json     The JSON formatted message to send
     */
    private void write(PlayerEndpoint endpoint, String json) {
        String name = endpoint.getName();
        // If there is no entry for this player, create an empty list to store the cached json
        cachedJson.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()));
        // If the write was unsuccessful, store the json
        if (!endpoint.write(json)) {
            cachedJson.get(name).add(json);
        }
    }

    /**
     * Get the Future that finishes first AND satisfies the supplied predicate. If no such Future exists, return null.
     *
     * @param futures   The futures to monitor.
     * @param predicate The condition to satisfy.
     * @param <T>       The Type of the Future result
     * @return The future that finished first and satisfied the predicate. If no such future exists, null.
     * @throws InterruptedException If the thread is interrupted.
     */
    private <T> Future<T> getFirst(Collection<Future<T>> futures, Predicate<T> predicate) throws InterruptedException {
        // Iterate while we are still waiting for some futures to complete
        while (!futures.isEmpty()) {
            // If the thread has been interrupted, rethrow the interruption to bubble it up
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // Iterate through all the futures
            for (Iterator<Future<T>> iterator = futures.iterator(); iterator.hasNext(); ) {
                Future<T> future = iterator.next();
                // If this future is done, process it
                if (future.isDone()) {
                    try {
                        // If it satisfies the predicate, return it now
                        if (predicate.test(future.get())) {
                            return future;
                        } else {
                            // Otherwise, remove it from the list of futures
                            iterator.remove();
                        }
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            // Yield to avoid busy waiting (not very clean but it works)
            Thread.yield();
        }
        // All futures have completed and returned null
        return null;
    }

    private String sanitize(String s) {
        // Sanitize camel case and pascal case
        // For example, ForeignAid => foreign aid
        return s.replaceAll("(?<=[a-z])(?=[A-Z])", " ").toLowerCase();
    }

    private <T> Future<T> getChoiceAsync(Player player, T[] choices, String prompt) {
        return executorService.submit(() -> getChoice(player, choices, prompt));
    }

    /**
     * Prompts a player for a selection from multiple choices.
     *
     * @param player  The player to prompt.
     * @param choices The choices available.
     * @param prompt  The message to display to the player.
     * @param <T>     The type of the choices.
     * @return The selected choice, or null if invalid.
     * @throws InterruptedException If the thread is interrupted.
     */
    private <T> T getChoice(Player player, T[] choices, String prompt) throws InterruptedException {
        // Map the choices to their string representations
        String[] choicesStr = Arrays.stream(choices).map(Object::toString).toArray(String[]::new);
        // Serialize as JSON
        Message message = new Message("choice", prompt);
        message.content = gson.toJsonTree(choicesStr);
        String json = gson.toJson(message);
        write(endpointMap.get(player), json);
        cachedJson.get(player.getName()).add(json); // re-add it to the cache, and we'll clear manually

        String responseJson = null;
        do {
            try {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                PlayerEndpoint endpoint = endpointMap.get(player);
                responseJson = endpoint.readLine();
            } catch (InterruptedException e) {
                if (lobby.shouldClose()) {
                    throw e;
                }
            }
        } while (responseJson == null);

        System.out.println("Got response: " + responseJson);
        cachedJson.get(player.getName()).remove(json);
        String response = gson.fromJson(responseJson, String.class); // The response will be a String
        // Find the choice that corresponds to the response
        for (int i = 0; i < choices.length; i++) {
            if (choicesStr[i].equals(response)) return choices[i];
        }
        // If the response was invalid, return null
        return null;
    }

    @Override
    protected void log(String format, Object... args) {
        // Broadcast a message of type log, with the log message as the content
        broadcast("log", String.format(format, args));
    }

    @Override
    protected void playerWon(Player player) {
        // info type messages will be on a popup, as opposed to the log
        broadcast("info", String.format("%s wins!", player.getName()));
    }

    @Override
    protected void update() {
        // An update message consists of all the Player objects serialized into a JSON array
        broadcast("update", Arrays.stream(players).filter(Objects::nonNull).toArray(Player[]::new));
    }

    @Override
    protected void playerDied(Player player) {
        // Alert everyone that a player died
        broadcast("info", String.format("%s died!", player.getName()));
    }

    @Override
    protected Player getTarget(Player player) throws InterruptedException {
        List<Player> options = new ArrayList<>(Arrays.asList(players));
        options.removeIf(Objects::isNull); // Dead players are not an option
        options.remove(player); // The player cannot target themself

        return getChoice(player, options.toArray(new Player[0]), "Choose a target");
    }

    @Override
    protected Action getAction(Player player) throws InterruptedException {
        // Find the possible options, based on how many coins the player has
        List<Action> options = new ArrayList<>();
        options.add(Action.Income);
        options.add(Action.ForeignAid);
        if (player.coins >= 7) options.add(Action.Coup);
        options.add(Action.Tax);
        if (player.coins >= 3) options.add(Action.Assassinate);
        options.add(Action.Exchange);
        options.add(Action.Steal);

        return getChoice(player, options.toArray(new Action[0]), "Choose an action");
    }

    @Override
    protected Card[] doExchange(Player player, Card[] cards) throws InterruptedException {
        // After an exchange, the player can only have as many cards as they started with.
        int influence = player.getInfluence();
        Card[] ret = new Card[influence];
        List<Card> cardList = new ArrayList<>(Arrays.asList(cards));
        // Populate the new hand array with selections from the available cards.
        for (int i = 0; i < ret.length; i++) {
            ret[i] = getChoice(player, cardList.toArray(new Card[0]), "Choose a card to keep");
            cardList.remove(ret[i]); // After each choice, remove that card from the options
        }

        return ret;
    }

    @Override
    protected CounterAction getCounterAction(Action action, Card card, Player player, Player target) throws InterruptedException {
        // If the action cannot be blocked or challenged, return immediately
        if (action != Action.ForeignAid && card == null) {
            return null;
        }

        // Since each choice is made asynchronously, we'll use a map to keep track of each player and the future corresponding to their choice
        Map<Future<String>, Player> futureMap = new HashMap<>();
        // This map keeps track of the text to display when prompting for a block for each card
        Map<String, Card> cardMap = new HashMap<>();
        // Build the message to display to the player when prompting for a counter action
        String message;
        if (card != null) {
            // The action uses a card, so specify which one
            message = String.format("%s is claiming to have a %s in order to %s", player, card, sanitize(action.name()));
            // If this action also has a target, specify who is being targeted
            if (target != null) message += " from " + target;
        } else {
            // No card is being used for this action, so just show who is doing what
            message = String.format("%s is trying to do %s", player, sanitize(action.name()));
        }

        // Foreign Aid is the only action that can be blocked by anyone
        if (action == Action.ForeignAid) {
            // For each player that is not the player doing the action, populate the map with the futures
            for (Player p : players) {
                if (p != player) {
                    Future<String> f = getChoiceAsync(p, new String[]{"Block (Duke)", "Pass"}, message);
                    futureMap.put(f, p);
                }
            }
        } else {
            // The action is not foreign aid, so this action can either be only challenged, or challenged and blocked
            List<String> choices = new ArrayList<>();
            // For each card that can block this action, add it to the choices
            for (Card c : action.blockedBy) {
                String s = String.format("Block (%s)", c);
                choices.add(s);
                cardMap.put(s, c);
            }
            // Challenging and passing are also options
            choices.add("Challenge");
            choices.add("Pass");
            // For each player, display their available options and populate the map with their futures
            for (Player p : players) {
                if (p != player && p != target) {
                    // If this player is not the player doing the action and not the target, they can either challenge or pass
                    Future<String> f = getChoiceAsync(p, new String[]{"Challenge", "Pass"}, message);
                    futureMap.put(f, p);
                } else if (p == target) {
                    // The target can either block, challenge, or pass, so display the available options we built earlier
                    Future<String> f = getChoiceAsync(p, choices.toArray(new String[0]), message);
                    futureMap.put(f, p);
                }
            }
        }
        // Get the first response to the prompt that is not a pass (or null, if everyone passed)
        Future<String> f = getFirst(futureMap.keySet(), s -> !s.equalsIgnoreCase("pass"));
        // Signal to all players that the choice has finished
        // It's ok (maybe necessary) to send to players that have already made the choice
        broadcast("stopChoice", null);
        // Cancel any futures and threads that haven't completed yet
        // Cancelling a completed thread will just no-op
        futureMap.keySet().forEach(future -> future.cancel(true));
        // If a player did a counter action, then return it now
        if (f != null) {
            try {
                String choice = f.get();
                Card c = cardMap.get(choice);
                return new CounterAction(c != null, futureMap.get(f), c);
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        // No player did a counter action
        return null;
    }

    @Override
    protected Card getCardToSacrifice(Player player) throws InterruptedException {
        // Since the player cards have non-null cards at the start of the array, we can copy the first n cards
        // where n is the influence of the player
        Card[] hand = new Card[player.getInfluence()];
        System.arraycopy(player.getCards(), 0, hand, 0, hand.length);
        // Prompt the player to choose from their available cards
        return getChoice(player, hand, "Choose a card to sacrifice");
    }

    private static class Message {
        /**
         * The type of the message. The type dictates how the client will process this.
         */
        public String type;
        /**
         * The message to display to the player.
         */
        public String message;
        /**
         * The JSON content of this message. How it's interpreted will depend on the content of {@link Message#type}
         */
        public JsonElement content;

        public Message(String type) {
            this.type = type;
        }

        public Message(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }
}
