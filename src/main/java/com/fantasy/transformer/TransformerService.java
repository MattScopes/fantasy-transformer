package com.fantasy.transformer;

import com.fantasy.transformer.models.internal.League;
import com.fantasy.transformer.models.internal.Team;
import com.fantasy.transformer.models.internal.Player;
import com.fantasy.transformer.models.sleeper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class TransformerService {

    @Autowired
    private SleeperClient sleeperClient;

    public League serveLeague(String sport,
                             String leagueId) throws IOException, InterruptedException {

        SleeperLeague sleeperLeague = Optional.ofNullable(sleeperClient.getLeague(leagueId))
            .orElse(new SleeperLeague());
        List<SleeperUser> sleeperUsers = Optional.ofNullable(sleeperClient.getUsers(leagueId))
            .orElse(Collections.emptyList());
        List<SleeperRoster> sleeperRosters = Optional.ofNullable(sleeperClient.getRosters(leagueId))
            .orElse(Collections.emptyList());
        Map<String, SleeperPlayer> sleeperPlayers = Optional.ofNullable(sleeperClient.getPlayers(sport))
            .orElse(Collections.emptyMap());

        return parseLeague(sleeperLeague, sleeperUsers, sleeperRosters, sleeperPlayers);
    }

    public League parseLeague(SleeperLeague sleeperLeague,
                              List<SleeperUser> sleeperUsers,
                              List<SleeperRoster> sleeperRosters,
                              Map<String, SleeperPlayer> sleeperPlayers) throws IOException, InterruptedException {

        League league = new League();

        Optional.ofNullable(sleeperLeague.getName())
            .ifPresent(league::setName);
        Optional.ofNullable(sleeperLeague.getSeason())
            .ifPresent(league::setSeason);
        Optional.ofNullable(sleeperLeague.getSport())
            .map(String::toUpperCase)
            .ifPresent(league::setSport);

        List<Team> teams = parseTeams(sleeperUsers, sleeperRosters, sleeperPlayers);

        league.setTeams(teams);

        return league;
    }

    private List<Team> parseTeams(List<SleeperUser> sleeperUsers,
                                  List<SleeperRoster> sleeperRosters,
                                  Map<String, SleeperPlayer> sleeperPlayers) {

        Map<String, SleeperUser> userMap = Optional.ofNullable(sleeperUsers)
            .orElse(Collections.emptyList())
            .stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(SleeperUser::getUserId, su -> su));

        List<Team> teams = sleeperRosters.stream()
            .filter(Objects::nonNull)
            .map(sleeperRoster -> parseTeam(userMap, sleeperRoster, sleeperPlayers))
            .collect(Collectors.toList());

        return teams.isEmpty() ? null : teams;
    }

    private Team parseTeam(Map<String, SleeperUser> userMap,
                           SleeperRoster sleeperRoster,
                           Map<String, SleeperPlayer> sleeperPlayers) {

        Team team = new Team();

        Optional.ofNullable(parseTeamName(userMap, sleeperRoster))
            .ifPresent(team::setName);
        Optional.ofNullable(sleeperRoster.getSettings())
            .map(SleeperRosterSettings::getWins)
            .ifPresent(team::setWins);
        Optional.ofNullable(sleeperRoster.getSettings())
            .map(SleeperRosterSettings::getLosses)
            .ifPresent(team::setLosses);
        Optional.ofNullable(sleeperRoster.getSettings())
            .map(SleeperRosterSettings::getTies)
            .ifPresent(team::setTies);
        Optional.ofNullable(sleeperRoster.getStarters())
            .map(v -> parsePlayers(sleeperPlayers, v))
            .ifPresent(team::setStarters);
        Optional.ofNullable(sleeperRoster.getReserve())
            .map(v -> parsePlayers(sleeperPlayers, v))
            .ifPresent(team::setReserves);
        Optional.ofNullable(sleeperRoster.getTaxi())
            .map(v -> parsePlayers(sleeperPlayers, v))
            .ifPresent(team::setTaxis);
        Optional.ofNullable(parseBenchPlayers(sleeperPlayers, sleeperRoster))
            .ifPresent(team::setBench);

        return team;
    }

    private List<Player> parseBenchPlayers(Map<String, SleeperPlayer> sleeperPlayers,
                                           SleeperRoster sleeperRoster) {

        // Bench players are those in the roster but not in starters, reserve, or taxi
        List<String> allPlayerIds = Optional.ofNullable(sleeperRoster.getPlayers())
            .orElse(Collections.emptyList());
        Set<String> excludeIds = new HashSet<>();

        Optional.ofNullable(sleeperRoster.getStarters())
            .ifPresent(excludeIds::addAll);
        Optional.ofNullable(sleeperRoster.getReserve())
            .ifPresent(excludeIds::addAll);
        Optional.ofNullable(sleeperRoster.getTaxi())
            .ifPresent(excludeIds::addAll);

        List<String> benchIds = allPlayerIds.stream()
            .filter(id -> !excludeIds.contains(id))
            .collect(Collectors.toList());
        List<Player> benchPlayers = parsePlayers(sleeperPlayers, benchIds);

        return (benchPlayers == null || benchPlayers.isEmpty()) ? null : benchPlayers;
    }

    private String parseTeamName(Map<String, SleeperUser> userMap,
                                 SleeperRoster sleeperRoster) {

        AtomicReference<String> teamName = new AtomicReference<>();

        Optional.ofNullable(sleeperRoster.getOwnerId())
            .map(userMap::get)
            .ifPresent(su -> {
                Optional.ofNullable(su.getMetadata())
                    .map(SleeperUserMetadata::getTeamName)
                    .ifPresent(teamName::set);
                if (teamName.get() == null || teamName.get().isEmpty()) {
                    Optional.ofNullable(su.getDisplayName())
                        .ifPresent(teamName::set);
                }
            });

        if (teamName.get() == null || teamName.get().isEmpty()) {
            Optional.ofNullable(sleeperRoster.getRosterId())
                .ifPresent(id -> teamName.set("Team " + id));
        }
        if (teamName.get() == null || teamName.get().isEmpty()) {
            teamName.set("Unknown Team");
        }

        return teamName.get();
    }

    private List<Player> parsePlayers(Map<String, SleeperPlayer> sleeperPlayers,
                                      List<String> SleeperPlayerIds) {

        List<Player> players = Optional.ofNullable(SleeperPlayerIds)
            .orElse(Collections.emptyList())
            .stream()
            .map(sleeperPlayers::get)
            .filter(Objects::nonNull)
            .map(sleeperPlayer -> parsePlayer(sleeperPlayers, sleeperPlayer.getPlayerId()))
            .collect(Collectors.toList());

        return players.isEmpty() ? null : players;
    }

    private Player parsePlayer(Map<String, SleeperPlayer> sleeperPlayers,
                               String SleeperPlayerId) {

        return Optional.ofNullable(sleeperPlayers.get(SleeperPlayerId))
            .map(sp -> {
                Player player = new Player();
                Optional.ofNullable(sp.getFirstName())
                    .ifPresent(player::setFirstName);
                Optional.ofNullable(sp.getLastName())
                    .ifPresent(player::setLastName);
                Optional.ofNullable(sp.getFantasyPositions())
                    .ifPresent(player::setPositions);
                Optional.ofNullable(sp.getTeam())
                    .ifPresent(player::setTeam);
                Optional.ofNullable(sp.getNumber())
                    .ifPresent(player::setNumber);
                return player;
            })
            .orElse(null);

    }

}
