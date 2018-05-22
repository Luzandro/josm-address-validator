// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.validation.tests;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Pair;
import org.openstreetmap.josm.tools.SubclassFilteredCollection;

/**
 * Performs validation tests on addresses (addr:housenumber) and associatedStreet relations.
 * @since 5644
 */
public class Addresses extends Test {

    protected static final int HOUSE_NUMBER_WITHOUT_STREET = 2601;
    protected static final int DUPLICATE_HOUSE_NUMBER = 2602;
    protected static final int MULTIPLE_STREET_NAMES = 2603;
    protected static final int MULTIPLE_STREET_RELATIONS = 2604;
    protected static final int HOUSE_NUMBER_TOO_FAR = 2605;

    // CHECKSTYLE.OFF: SingleSpaceSeparator
    protected static final String ADDR_HOUSE_NUMBER  = "addr:housenumber";
    protected static final String ADDR_INTERPOLATION = "addr:interpolation";
    protected static final String ADDR_NEIGHBOURHOOD = "addr:neighbourhood";
    protected static final String ADDR_PLACE         = "addr:place";
    protected static final String ADDR_STREET        = "addr:street";
    protected static final String ADDR_CITY          = "addr:city";
    protected static final String ADDR_UNIT          = "addr:unit";
    protected static final String ADDR_FLATS         = "addr:flats";
    protected static final String ADDR_HOUSE_NAME    = "addr:housename";
    protected static final String ADDR_POSTCODE      = "addr:postcode";
    protected static final String ASSOCIATED_STREET  = "associatedStreet";
    // CHECKSTYLE.ON: SingleSpaceSeparator

    private Map<String, Collection<OsmPrimitive>> addresses = null;
    private Set<String> ignored_addresses = null;

    /**
     * Constructor
     */
    public Addresses() {
        super(tr("Addresses"), tr("Checks for errors in addresses and associatedStreet relations."));
    }

    protected List<Relation> getAndCheckAssociatedStreets(OsmPrimitive p) {
        List<Relation> list = OsmPrimitive.getFilteredList(p.getReferrers(), Relation.class);
        list.removeIf(r -> !r.hasTag("type", ASSOCIATED_STREET));
        if (list.size() > 1) {
            Severity level;
            // warning level only if several relations have different names, see #10945
            final String name = list.get(0).get("name");
            if (name == null || SubclassFilteredCollection.filter(list, r -> r.hasTag("name", name)).size() < list.size()) {
                level = Severity.WARNING;
            } else {
                level = Severity.OTHER;
            }
            List<OsmPrimitive> errorList = new ArrayList<>(list);
            errorList.add(0, p);
            errors.add(TestError.builder(this, level, MULTIPLE_STREET_RELATIONS)
                    .message(tr("Multiple associatedStreet relations"))
                    .primitives(errorList)
                    .build());
        }
        return list;
    }

    protected void checkHouseNumbersWithoutStreet(OsmPrimitive p) {
        List<Relation> associatedStreets = getAndCheckAssociatedStreets(p);
        // Find house number without proper location
        // (neither addr:street, associatedStreet, addr:place, addr:neighbourhood or addr:interpolation)
        if (p.hasKey(ADDR_HOUSE_NUMBER) && !p.hasKey(ADDR_STREET, ADDR_PLACE, ADDR_NEIGHBOURHOOD)) {
            for (Relation r : associatedStreets) {
                if (r.hasTag("type", ASSOCIATED_STREET)) {
                    return;
                }
            }
            for (Way w : OsmPrimitive.getFilteredList(p.getReferrers(), Way.class)) {
                if (w.hasKey(ADDR_INTERPOLATION) && w.hasKey(ADDR_STREET)) {
                    return;
                }
            }
            // No street found
            errors.add(TestError.builder(this, Severity.WARNING, HOUSE_NUMBER_WITHOUT_STREET)
                    .message(tr("House number without street"))
                    .primitives(p)
                    .build());
        }
    }

    private boolean isPOI(OsmPrimitive p) {
        return p.hasKey("shop", "amenity", "tourism", "leisure", "emergency", "craft", "entrance", "name");
    }

    private boolean hasAddress(OsmPrimitive p) {
        return (p.hasKey(ADDR_HOUSE_NUMBER) && p.hasKey(ADDR_STREET, ADDR_PLACE));
    }

    /**
     * adds the OsmPrimitive to the address map if it complies to the restrictions
     * @param p OsmPrimitive that has an address
     */
    private void collectAddress(OsmPrimitive p) {
        if (isPOI(p)) {
            return;
        }
        String simplified_address = getSimplifiedAddress(p);
        if (!ignored_addresses.contains(simplified_address)) {
            if (addresses.containsKey(simplified_address)) {
                addresses.get(simplified_address).add(p);
            }
            else {
                ArrayList<OsmPrimitive> objects = new ArrayList<>();
                objects.add(p);
                addresses.put(simplified_address, objects);
            }
        }
    }

    protected void initAddressMap(OsmPrimitive primitive) {
        addresses = new HashMap<>();
        ignored_addresses = new HashSet<>();
        Collection<OsmPrimitive> primitives = primitive.getDataSet().allPrimitives();
        String simplified_address;
        for (OsmPrimitive p : primitives) {
            if (p.hasKey(ADDR_UNIT) && p instanceof Node) {
                for (OsmPrimitive r : p.getReferrers()) {
                    if (hasAddress(r)) {
                        // ignore addresses of buildings that are connected to addr:unit nodes
                        // it's quite reasonable that there are more buildings with this address
                        simplified_address = getSimplifiedAddress(r);
                        if (!ignored_addresses.contains(simplified_address)) {
                            ignored_addresses.add(simplified_address);
                        }
                        else if (addresses.containsKey(simplified_address)) {
                                addresses.remove(simplified_address);
                        }
                    }
                }
            }
            if (hasAddress(p)) {
                collectAddress(p);
            }
        }
    }

    @Override
    public void endTest() {
        addresses = null;
        ignored_addresses = null;
        super.endTest();
    }

    protected void checkForDuplicate(OsmPrimitive p) {
        if (this.addresses == null) {
            initAddressMap(p);
        }
        if (!isPOI(p) && hasAddress(p)) {
            String simplified_address = getSimplifiedAddress(p);
            if (ignored_addresses.contains(simplified_address)) {
                return;
            }
            if (addresses.containsKey(simplified_address)) {
                for (OsmPrimitive p2 : addresses.get(simplified_address)) {
                    if (p == p2) {
                        continue;
                    }
                    Severity severity_level = Severity.WARNING;
                    List<OsmPrimitive> primitives = new ArrayList<>(2);
                    primitives.add(p);
                    primitives.add(p2);
                    String city1 = p.get(ADDR_CITY);
                    String city2 = p2.get(ADDR_CITY);
                    double distance = getDistance(p, p2);
                    if (city1 != null && city2 != null) {
                        if (city1.equals(city2)) {
                            if(!p.hasKey(ADDR_POSTCODE) || !p2.hasKey(ADDR_POSTCODE) || p.get(ADDR_POSTCODE).equals(p2.get(ADDR_POSTCODE))) {
                                severity_level = Severity.WARNING;
                            }
                            else {
                                // address including city identical but postcode differs
                                // most likely perfectly fine
                                severity_level = Severity.OTHER;
                            }
                        }
                        else {
                            // address differs only by city - notify if very close, otherwise ignore
                            if (distance < 200.0) {
                                severity_level = Severity.OTHER;
                            }
                        }

                    }
                    else {
                        // at least one address has no city specified
                        if (p.hasKey(ADDR_POSTCODE) && p2.hasKey(ADDR_POSTCODE) && p.get(ADDR_POSTCODE).equals(p2.get(ADDR_POSTCODE))) {
                            // address including postcode identical
                            severity_level = Severity.WARNING;
                        }
                        else {
                            // city/postcode unclear - warn if very close, otherwise only notify
                            // TODO: get city from surrounding boundaries?
                            if (distance < 200.0) {
                                severity_level = Severity.WARNING;
                            }
                            else {
                                severity_level = Severity.OTHER;
                            }
                        }
                    }
                    errors.add(TestError.builder(this, severity_level, DUPLICATE_HOUSE_NUMBER)
                            .message(tr("Duplicate house numbers"), marktr("''{0}'' ({1}m)"), simplified_address, (int) distance).primitives(primitives).build());
                }
            }
        }
    }

    private String getSimplifiedAddress(OsmPrimitive p) {
        String simplified_street_name = p.hasKey(ADDR_STREET) ? p.get(ADDR_STREET) : p.get(ADDR_PLACE);
        // ignore whitespaces and dashes in street name, so that "Mozart-Gasse", "Mozart Gasse" and "Mozartgasse" are all seen as equal
        simplified_street_name = simplified_street_name.toUpperCase().replaceAll("[ -]", "");
        String simplified_address = Stream.of(simplified_street_name, p.get(ADDR_HOUSE_NUMBER), p.get(ADDR_HOUSE_NAME), p.get(ADDR_UNIT), p.get(ADDR_FLATS))
            .map(s -> (s == null ? "" : s))
            .collect(Collectors.joining(" "));
        simplified_address = simplified_address.trim().toUpperCase();
        return simplified_address;
    }

    @Override
    public void visit(Node n) {
        checkHouseNumbersWithoutStreet(n);
        checkForDuplicate(n);
    }

    @Override
    public void visit(Way w) {
        checkHouseNumbersWithoutStreet(w);
        checkForDuplicate(w);
    }

    @Override
    public void visit(Relation r) {
        checkHouseNumbersWithoutStreet(r);
        checkForDuplicate(r);
        if (r.hasTag("type", ASSOCIATED_STREET)) {
            // Used to count occurences of each house number in order to find duplicates
            Map<String, List<OsmPrimitive>> map = new HashMap<>();
            // Used to detect different street names
            String relationName = r.get("name");
            Set<OsmPrimitive> wrongStreetNames = new HashSet<>();
            // Used to check distance
            Set<OsmPrimitive> houses = new HashSet<>();
            Set<Way> street = new HashSet<>();
            for (RelationMember m : r.getMembers()) {
                String role = m.getRole();
                OsmPrimitive p = m.getMember();
                if ("house".equals(role)) {
                    houses.add(p);
                    String number = p.get(ADDR_HOUSE_NUMBER);
                    if (number != null) {
                        number = number.trim().toUpperCase(Locale.ENGLISH);
                        List<OsmPrimitive> list = map.get(number);
                        if (list == null) {
                            list = new ArrayList<>();
                            map.put(number, list);
                        }
                        list.add(p);
                    }
                    if (relationName != null && p.hasKey(ADDR_STREET) && !relationName.equals(p.get(ADDR_STREET))) {
                        if (wrongStreetNames.isEmpty()) {
                            wrongStreetNames.add(r);
                        }
                        wrongStreetNames.add(p);
                    }
                } else if ("street".equals(role)) {
                    if (p instanceof Way) {
                        street.add((Way) p);
                    }
                    if (relationName != null && p.hasTagDifferent("name", relationName)) {
                        if (wrongStreetNames.isEmpty()) {
                            wrongStreetNames.add(r);
                        }
                        wrongStreetNames.add(p);
                    }
                }
            }
            // Report duplicate house numbers
            for (Entry<String, List<OsmPrimitive>> entry : map.entrySet()) {
                List<OsmPrimitive> list = entry.getValue();
                if (list.size() > 1) {
                    errors.add(TestError.builder(this, Severity.WARNING, DUPLICATE_HOUSE_NUMBER)
                            .message(tr("Duplicate house numbers"), marktr("House number ''{0}'' duplicated"), entry.getKey())
                            .primitives(list)
                            .build());
                }
            }
            // Report wrong street names
            if (!wrongStreetNames.isEmpty()) {
                errors.add(TestError.builder(this, Severity.WARNING, MULTIPLE_STREET_NAMES)
                        .message(tr("Multiple street names in relation"))
                        .primitives(wrongStreetNames)
                        .build());
            }
            // Report addresses too far away
            if (!street.isEmpty()) {
                for (OsmPrimitive house : houses) {
                    if (house.isUsable()) {
                        checkDistance(house, street);
                    }
                }
            }
        }
    }

    /**
     * returns rough distance between two OsmPrimitives
     * @param a
     * @param b
     * @return distance of center of bounding boxes in meters
     */
    private double getDistance(OsmPrimitive a, OsmPrimitive b) {
        LatLon center_a = a.getBBox().getCenter();
        LatLon center_b = b.getBBox().getCenter();
        return (center_a.greatCircleDistance(center_b));
    }

    protected void checkDistance(OsmPrimitive house, Collection<Way> street) {
        EastNorth centroid;
        if (house instanceof Node) {
            centroid = ((Node) house).getEastNorth();
        } else if (house instanceof Way) {
            List<Node> nodes = ((Way) house).getNodes();
            if (house.hasKey(ADDR_INTERPOLATION)) {
                for (Node n : nodes) {
                    if (n.hasKey(ADDR_HOUSE_NUMBER)) {
                        checkDistance(n, street);
                    }
                }
                return;
            }
            centroid = Geometry.getCentroid(nodes);
        } else {
            return; // TODO handle multipolygon houses ?
        }
        if (centroid == null) return; // fix #8305
        double maxDistance = Config.getPref().getDouble("validator.addresses.max_street_distance", 200.0);
        boolean hasIncompleteWays = false;
        for (Way streetPart : street) {
            for (Pair<Node, Node> chunk : streetPart.getNodePairs(false)) {
                EastNorth p1 = chunk.a.getEastNorth();
                EastNorth p2 = chunk.b.getEastNorth();
                if (p1 != null && p2 != null) {
                    EastNorth closest = Geometry.closestPointToSegment(p1, p2, centroid);
                    if (closest.distance(centroid) <= maxDistance) {
                        return;
                    }
                } else {
                    Logging.warn("Addresses test skipped chunck "+chunk+" for street part "+streetPart+" because p1 or p2 is null");
                }
            }
            if (!hasIncompleteWays && streetPart.isIncomplete()) {
                hasIncompleteWays = true;
            }
        }
        // No street segment found near this house, report error on if the relation does not contain incomplete street ways (fix #8314)
        if (hasIncompleteWays) return;
        List<OsmPrimitive> errorList = new ArrayList<>(street);
        errorList.add(0, house);
        errors.add(TestError.builder(this, Severity.WARNING, HOUSE_NUMBER_TOO_FAR)
                .message(tr("House number too far from street"))
                .primitives(errorList)
                .build());
    }
}
