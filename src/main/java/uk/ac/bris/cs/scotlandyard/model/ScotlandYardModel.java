package uk.ac.bris.cs.scotlandyard.model;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableCollection;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static uk.ac.bris.cs.scotlandyard.model.Colour.BLACK;
import static uk.ac.bris.cs.scotlandyard.model.Colour.BLUE;
import static uk.ac.bris.cs.scotlandyard.model.Ticket.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import uk.ac.bris.cs.gamekit.graph.Edge;
import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.gamekit.graph.ImmutableGraph;
import uk.ac.bris.cs.gamekit.graph.Node;
import uk.ac.bris.cs.scotlandyard.harness.ImmutableScotlandYardView;

// TODO implement all methods and pass all tests
public class ScotlandYardModel implements ScotlandYardGame, Consumer<Move> {

	List<Boolean> rounds;
	Graph<Integer, Transport> graph;
	ArrayList<ScotlandYardPlayer> players = new ArrayList<>();
	ArrayList<Spectator> spectators = new ArrayList<>();
	Colour currentPlayer;
	int currentRound;
	int mrXLastLocation;
	boolean currentPlayerResponded;

	public ScotlandYardModel(List<Boolean> rounds, Graph<Integer, Transport> graph,
							 PlayerConfiguration mrX, PlayerConfiguration firstDetective,
							 PlayerConfiguration... restOfTheDetectives) {

		if (rounds.isEmpty()) {
			throw new IllegalArgumentException("Empty rounds");
		} else {
			this.rounds = requireNonNull(rounds);
		}

		if (graph.isEmpty()) {
			throw new IllegalArgumentException("Empty graph");
		} else {
			this.graph = requireNonNull(graph);
		}

		if (!mrX.colour.isMrX()) {
			throw new IllegalArgumentException("MrX should be Black");
		}

		ArrayList<PlayerConfiguration> configurations = new ArrayList<>();
		for (PlayerConfiguration configuration : restOfTheDetectives)
			configurations.add(requireNonNull(configuration));
		configurations.add(0, firstDetective);
		configurations.add(0, mrX);

		Set<Integer> locationSet = new HashSet<>();
		for (PlayerConfiguration configuration : configurations) {
			if (locationSet.contains(configuration.location))
				throw new IllegalArgumentException("Duplicate location");
			locationSet.add(configuration.location);
		}

		Set<Colour> colourSet = new HashSet<>();
		for (PlayerConfiguration configuration : configurations) {
			if (colourSet.contains(configuration.colour))
				throw new IllegalArgumentException("Duplicate colour");
			colourSet.add(configuration.colour);
		}

		for (PlayerConfiguration configuration : configurations) {
			if (!(configuration.tickets.containsKey(Ticket.TAXI) && configuration.tickets.containsKey(Ticket.BUS)
					&& configuration.tickets.containsKey(Ticket.UNDERGROUND) && configuration.tickets.containsKey(Ticket.SECRET)
					&& configuration.tickets.containsKey(Ticket.DOUBLE)))
				throw new IllegalArgumentException("Missing ticket type");

			if (configuration.colour != BLACK) {
				if (configuration.tickets.get(SECRET) != 0)
					throw new IllegalArgumentException("Detective has secret ticket");
				if (configuration.tickets.get(DOUBLE) != 0)
					throw new IllegalArgumentException("Detective has double ticket");
			}

			players.add(new ScotlandYardPlayer(configuration.player, configuration.colour, configuration.location,
					configuration.tickets));
		}

		currentPlayer = BLACK;
		currentRound = NOT_STARTED;
        mrXLastLocation = 0;
    }


	@Override
	public void registerSpectator(Spectator spectator) {
		if(spectators.contains(spectator)) throw new IllegalArgumentException("Spectator already registered");
		spectators.add(requireNonNull(spectator));
	}

	@Override
	public void unregisterSpectator(Spectator spectator) {
		requireNonNull(spectator);
		if(!spectators.contains(spectator)) throw new IllegalArgumentException("Spectator was never registered");
		spectators.remove(spectator);
	}

	@Override
	public void startRotate() {
		if (isGameOver()) throw new IllegalStateException("game over");
		takeCurrentPlayerTurn();
	}

	private void takeCurrentPlayerTurn(){
		int currentLocation;
		if(currentPlayer.isDetective()) currentLocation = getPlayerLocation(currentPlayer).get();
		else currentLocation = players.get(0).location();

		Set<Move> moves = generateValidMoves(currentPlayer, currentLocation);
		requireNonNull(moves);

		currentPlayerResponded = false;
		getPlayer(currentPlayer).get().makeMove(this, currentLocation, moves, this);
		if(!currentPlayerResponded) return;

		if(isGameOver()) {
			spectators.forEach((s) -> s.onGameOver(this, getWinningPlayers()));
		} else {
			if (currentPlayer.isDetective()) takeCurrentPlayerTurn();
			else spectators.forEach((s) -> s.onRotationComplete(this));
		}
	}

	private Colour nextPlayer(ScotlandYardPlayer currentPlayer){
		int i = players.indexOf(currentPlayer) + 1;
		if(i < players.size()) return getPlayers().get(i);
		return BLACK;
	}

	private Set<Move> generateValidMoves(Colour player, int location) {
		Set<Move> moves = new HashSet<>();
		Set<Integer> detectiveLocations = new HashSet<>();
		for(int i=1; i<players.size(); i++)
			detectiveLocations.add(players.get(i).location());

		for(Edge<Integer, Transport> edge : graph.getEdgesFrom(graph.getNode(location))) {
			int destination = edge.destination().value();
			Ticket ticket = Ticket.fromTransport(edge.data());
			if(!(detectiveLocations.contains(destination))){
				if(getPlayerTickets(player, ticket).get() > 0) {
					TicketMove firstMove = new TicketMove(player, ticket, destination);
					moves.add(firstMove);
					if(getPlayerTickets(player, DOUBLE).get() > 0 && currentRound <= rounds.size() - 2) {
						Set<TicketMove> secondMoves = generateSecondMoves(player, destination, ticket, detectiveLocations);
						for(TicketMove secondMove : secondMoves)
							moves.add(new DoubleMove(player, firstMove, secondMove));
					}
				}
				if (getPlayerTickets(player, SECRET).get() > 0) {
					TicketMove firstMove = new TicketMove(player, SECRET, destination);
					moves.add(firstMove);
					if(getPlayerTickets(player, DOUBLE).get() > 0 && currentRound <= rounds.size() - 2) {
						Set<TicketMove> secondMoves = generateSecondMoves(player, destination, SECRET, detectiveLocations);
						for(TicketMove secondMove : secondMoves)
							moves.add(new DoubleMove(player, firstMove, secondMove));
					}
				}
			}
		}
		if(player.isDetective() && moves.isEmpty())
			moves.add(new PassMove(player));

		return moves;
	}

	private Set<TicketMove> generateSecondMoves(Colour player, int location, Ticket firstTicketUsed, Set<Integer> detectiveLocations) {
		Set<TicketMove> moves = new HashSet<>();
		for(Edge<Integer, Transport> edge : graph.getEdgesFrom(graph.getNode(location))) {
			int destination = edge.destination().value();
			Ticket ticket = Ticket.fromTransport(edge.data());
			if(!(detectiveLocations.contains(destination))){

				int requiredTickets = 1;
				if(firstTicketUsed.equals(ticket))
					requiredTickets = 2;
				if(getPlayerTickets(player, ticket).get() >= requiredTickets) {
					TicketMove firstMove = new TicketMove(player, ticket, destination);
					moves.add(firstMove);
				}

				requiredTickets = 1;
				if(firstTicketUsed.equals(SECRET))
					requiredTickets = 2;
				if (getPlayerTickets(player, SECRET).get() >= requiredTickets) {
					TicketMove firstMove = new TicketMove(player, SECRET, destination);
					moves.add(firstMove);
				}
			}
		}
		return moves;
	}

	private Optional<Player> getPlayer(Colour player) {
		for(ScotlandYardPlayer SYPlayer : players) {
			if(player.equals(SYPlayer.colour())) {
				return Optional.of(SYPlayer.player());
			}
		}
		return Optional.empty();
	}

	@Override
	public Collection<Spectator> getSpectators() {
		return Collections.unmodifiableList(spectators);
	}

	@Override
	public List<Colour> getPlayers() {
		List<Colour> colours = new ArrayList<>();
		for(ScotlandYardPlayer player : players)
			colours.add(player.colour());
		return Collections.unmodifiableList(colours);
	}

	@Override
	public Set<Colour> getWinningPlayers() {
		Set<Colour> detectives = new HashSet<>();
		for(int i=1; i<players.size(); i++) detectives.add(players.get(i).colour());
		Set<Colour> mrX = new HashSet<>();
		mrX.add(BLACK);
		if(isGameOver()) {
			if (generateValidMoves(BLACK, players.get(0).location()).isEmpty())
				return Collections.unmodifiableSet(detectives);

			for (ScotlandYardPlayer player : players) {
				if(player.colour().isDetective()) {
					if(players.get(0).location() == player.location()) {
						return Collections.unmodifiableSet(detectives);
					}
				}
			}
			return Collections.unmodifiableSet(mrX);
		}
		return Collections.unmodifiableSet(new HashSet<>());
	}

	@Override
	public Optional<Integer> getPlayerLocation(Colour colour) {
		if(colour.isMrX()) {
			return Optional.of(mrXLastLocation);
		}
		for(ScotlandYardPlayer player : players) {
			if(colour.equals(player.colour())) {
				return Optional.of(player.location());
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<Integer> getPlayerTickets(Colour colour, Ticket ticket) {
		for(ScotlandYardPlayer player : players) {
			if(colour.equals(player.colour())) {
				return Optional.of(player.tickets().get(ticket));
			}
		}
		return Optional.empty();
	}

	@Override
	public boolean isGameOver() {
		boolean gameOver = true;
		for (ScotlandYardPlayer player : players) {
			if (player.colour().isDetective()) {
				if (player.tickets().get(TAXI) > 0 || player.tickets().get(BUS) > 0 || player.tickets().get(UNDERGROUND) > 0) gameOver = false;
				if (players.get(0).location() == player.location()) return true;
			}
		}
		if(generateValidMoves(BLACK, players.get(0).location()).isEmpty() && currentPlayer.isMrX()) return true;
		if(currentRound >= rounds.size() && currentPlayer.isMrX()) return true;
		return gameOver;
	}

	@Override
	public Colour getCurrentPlayer() {
		return currentPlayer;
	}

	@Override
	public int getCurrentRound() {
		return currentRound;
	}

	@Override
	public List<Boolean> getRounds() {
		return Collections.unmodifiableList(rounds);
	}

	@Override
	public Graph<Integer, Transport> getGraph() {
		return new ImmutableGraph<>(graph);
	}

	private void makeMove(TicketMove move, ScotlandYardPlayer SYPlayer) {
		if(!generateValidMoves(move.colour(), SYPlayer.location()).contains(move)) throw new IllegalArgumentException("Move selected not in valid moves");
		SYPlayer.location(move.destination());
		if(move.colour().isDetective()) players.get(0).addTicket(move.ticket());
		else {
			if(rounds.get(currentRound)) mrXLastLocation = SYPlayer.location();
			currentRound++;
		}
		SYPlayer.removeTicket(move.ticket());
	}

	public void accept(Move move) {
		currentPlayerResponded = true;

		ScotlandYardPlayer SYPlayer = players.get(0);
		for(ScotlandYardPlayer player : players) {
			if(move.colour().equals(player.colour())) {
				SYPlayer = player;
			}
		}
		currentPlayer = nextPlayer(SYPlayer);

		if(move instanceof DoubleMove) {
			DoubleMove doubleMove = (DoubleMove) move;
			SYPlayer.removeTicket(DOUBLE);

			int firstDestination, secondDestination;
			if(rounds.get(currentRound)) firstDestination = doubleMove.firstMove().destination();
			else firstDestination = mrXLastLocation;
			if(rounds.get(currentRound + 1)) secondDestination = doubleMove.finalDestination();
			else secondDestination = firstDestination;

			spectators.forEach((s) -> s.onMoveMade(this, new DoubleMove(BLACK, doubleMove.firstMove().ticket(), firstDestination, doubleMove.secondMove().ticket(), secondDestination)));

			makeMove(doubleMove.firstMove(), SYPlayer);
			spectators.forEach((s) -> s.onRoundStarted(this, currentRound));
			spectators.forEach((s) -> s.onMoveMade(this, new TicketMove(BLACK, doubleMove.firstMove().ticket(), firstDestination)));


			makeMove(doubleMove.secondMove(), SYPlayer);
			spectators.forEach((s) -> s.onRoundStarted(this, currentRound));
			spectators.forEach((s) -> s.onMoveMade(this, new TicketMove(BLACK, doubleMove.secondMove().ticket(), secondDestination)));

		} else if (move instanceof TicketMove) {
			TicketMove ticketMove = (TicketMove) move;

			int destination;
			if(ticketMove.colour().isDetective() || (rounds.get(currentRound) && ticketMove.colour().isMrX())) destination = ticketMove.destination();
			else destination = mrXLastLocation;

			makeMove(ticketMove, SYPlayer);

			if(SYPlayer.colour().isMrX()) spectators.forEach((s) -> s.onRoundStarted(this, currentRound));
			spectators.forEach((s) -> s.onMoveMade(this, new TicketMove(ticketMove.colour(), ticketMove.ticket(), destination)));

		} else {
			spectators.forEach((s) -> s.onMoveMade(this, move));
		}
	}

}