/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import static java.lang.Math.*;
import java.util.regex.*;

/**
 * Algorithms from https://www.movable-type.co.uk/scripts/latlong.html
 * <br>Assumes a spherical earth, a radius of 6371e3 meters.
 */
public class LL {

    /**
     * latitude and longitude in degrees, following map conventions
     */
    public final double lat, lon;
    public LL() {
        this(Double.NaN, Double.NaN);
    }
    public LL(double y, double x) {
        lat = y;
        lon = x;
    }
    public LL(String ll) {
        if (ll != null) {
            String[] vs = ll.split(", *");
            if (vs.length == 2) {
                lat = parseAngle(vs[0]);
                lon = parseAngle(vs[1]);
                return;
            }
        }
        lat = Double.NaN;
        lon = Double.NaN;
    }
    public boolean isValid() {
        return !Double.isNaN(lat);
    }
    public double distanceM(LL p) {
        double R = 6371e3; // metres
        double φ1 = toRadians(lat);
        double φ2 = toRadians(p.lat);
        double Δφ = toRadians(p.lat - lat);
        double Δλ = toRadians(p.lon - lon);

        // Haversine method
        double a = sin(Δφ / 2) * sin(Δφ / 2)
                + cos(φ1) * cos(φ2)
                * sin(Δλ / 2) * sin(Δλ / 2);
        double c = 2 * atan2(sqrt(a), sqrt(1 - a));

        return R * c;
    }
    static double parseAngle(String a) {
        if (a == null)
            return 0;
        double v = 0;
        boolean neg = false;
        final int len = a.length();
        int ndig = 0;
        int denom1 = 1;
        double denom2 = 1;
        double dv = 0;
        boolean seenDot = false;
        for (int pos = 0; pos < len; pos++) {
            char c = a.charAt(pos);
            switch (c) {
                case '.':
                    if (seenDot)
                        return Double.NaN;
                    seenDot = true;
                    break;
                case '+':
                case 'E':
                case 'e':
                case 'N':
                case 'n':
                    break;
                case '-':
                case 'W':
                case 'w':
                case 'S':
                case 's':
                    neg = !neg;
                    break;
                case '˚':
                case '°':
                    v += dv / denom2;
                    denom1 = 60;
                    denom2 = 1;
                    ndig = 0;
                    seenDot = false;
                    dv = 0;
                    break;
                case '\'':
                case '′':
                    v += dv / (denom2 * 60);
                    denom1 = 60 * 60 * 60;
                    denom2 = 1;
                    ndig = 0;
                    seenDot = false;
                    dv = 0;
                    break;
                case '″':
                case '"':
                    v += dv / (denom2 * (60 * 60));
                    denom1 = 60 * 60;
                    denom2 = 1;
                    ndig = 0;
                    seenDot = false;
                    dv = 0;
                    break;
                case ' ':
                case '\u202f':
                    if (ndig == 0)
                        break;
                    v += dv / (denom1 * denom2);
                    denom1 *= 60;
                    denom2 = 1;
                    ndig = 0;
                    seenDot = false;
                    dv = 0;
                    break;
                default:
                    if ('0' <= c && c <= '9') {
                        dv = c - '0' + dv * 10;
                        if (seenDot)
                            denom2 *= 10;
                        ndig++;
                        break;
                    } else {
                        System.out.printf("Illegal character in angle: \\u%x  '%c'\n", (int)c, c);
                        return Double.NaN;
                    }
            }
        }
        v += dv / denom1 / denom2;
        return neg ? -v : v;
    }
    static final private Pattern n = Pattern.compile("[-+]?[0-9]+(.[0-9]*)?");
    
    ///-----------------
    /* - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  */
/* Latitude/longitude spherical geodesy tools                         (c) Chris Veness 2002-2017  */
/*                                                                                   MIT Licence  */
/* www.movable-type.co.uk/scripts/latlong.html                                                    */
/* www.movable-type.co.uk/scripts/geodesy/docs/module-latlon-spherical.html                       */
/* - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  */


/**
 * Library of geodesy functions for operations on a spherical earth model.

 */


/**
 * Creates a LatLon point on the earth's surface at the specified latitude / longitude.
 *
 * @constructor
 * @param {number} lat - Latitude in degrees.
 * @param {number} lon - Longitude in degrees.
 *
 * @example
 *     double p1 = new LL(52.205, 0.119);
 */


/**
 * Returns the distance from ‘this’ point to destination point (using haversine formula).
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @param   {number} [radius=6371e3] - (Mean) radius of earth (defaults to radius in metres).
 * @returns {number} Distance between this point and destination point, in same units as radius.
 *
 * @example
 *     double p1 = new LL(52.205, 0.119);
 *     double p2 = new LL(48.857, 2.351);
 *     double d = p1.distanceTo(p2); // 404.3 km
 */
public double distanceTo(LL point, double radius) {

    // a = sin²(Δφ/2) + cos(φ1)⋅cos(φ2)⋅sin²(Δλ/2)
    // tanδ = √(a) / √(1−a)
    // see mathforum.org/library/drmath/view/51879.html for derivation

    double R = radius;
    double φ1 = toRadians(lat),  λ1 = toRadians(lon);
    double φ2 = toRadians(point.lat), λ2 = toRadians(point.lon);
    double Δφ = φ2 - φ1;
    double Δλ = λ2 - λ1;

    double a = sin(Δφ/2) * sin(Δφ/2)
          + cos(φ1) * cos(φ2)
          * sin(Δλ/2) * sin(Δλ/2);
    double c = 2 * atan2(sqrt(a), sqrt(1-a));
    double d = R * c;

    return d;
};


/**
 * Returns the (initial) bearing from ‘this’ point to destination point.
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @returns {number} Initial bearing in degrees from north.
 *
 * @example
 *     double p1 = new LL(52.205, 0.119);
 *     double p2 = new LL(48.857, 2.351);
 *     double b1 = p1.bearingTo(p2); // 156.2°
 */
public double bearingTo(LL point) {

    // tanθ = sinΔλ⋅cosφ2 / cosφ1⋅sinφ2 − sinφ1⋅cosφ2⋅cosΔλ
    // see mathforum.org/library/drmath/view/55417.html for derivation

    double φ1 = toRadians(lat), φ2 = toRadians(point.lat);
    double Δλ = toRadians(point.lon-lon);
    double y = sin(Δλ) * cos(φ2);
    double x = cos(φ1)*sin(φ2) -
            sin(φ1)*cos(φ2)*cos(Δλ);
    double θ = atan2(y, x);

    return (toDegrees(θ)+360) % 360;
};


/**
 * Returns final bearing arriving at destination destination point from ‘this’ point; the final bearing
 * will differ from the initial bearing by varying degrees according to distance and latitude.
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @returns {number} Final bearing in degrees from north.
 *
 * @example
 *     double p1 = new LL(52.205, 0.119);
 *     double p2 = new LL(48.857, 2.351);
 *     double b2 = p1.finalBearingTo(p2); // 157.9°
 */
public double finalBearingTo(LL point) {

    // get initial bearing from destination point to this point & reverse it by adding 180°
    return ( point.bearingTo(this)+180 ) % 360;
};


/**
 * Returns the midpoint between ‘this’ point and the supplied point.
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @returns {LatLon} Midpoint between this point and the supplied point.
 *
 * @example
 *     double p1 = new LL(52.205, 0.119);
 *     double p2 = new LL(48.857, 2.351);
 *     double pMid = p1.midpointTo(p2); // 50.5363°N, 001.2746°E
 */
public LL midpointTo(LL point) {

    // φm = atan2( sinφ1 + sinφ2, √( (cosφ1 + cosφ2⋅cosΔλ) ⋅ (cosφ1 + cosφ2⋅cosΔλ) ) + cos²φ2⋅sin²Δλ )
    // λm = λ1 + atan2(cosφ2⋅sinΔλ, cosφ1 + cosφ2⋅cosΔλ)
    // see mathforum.org/library/drmath/view/51822.html for derivation

    double φ1 = toRadians(lat), λ1 = toRadians(lon);
    double φ2 = toRadians(point.lat);
    double Δλ = toRadians(point.lon-lon);

    double Bx = cos(φ2) * cos(Δλ);
    double By = cos(φ2) * sin(Δλ);

    double x = sqrt((cos(φ1) + Bx) * (cos(φ1) + Bx) + By * By);
    double y = sin(φ1) + sin(φ2);
    double φ3 = atan2(y, x);

    double λ3 = λ1 + atan2(By, cos(φ1) + Bx);

    return new LL(toDegrees(φ3), (toDegrees(λ3)+540)%360-180); // normalise to −180..+180°
};


/**
 * Returns the point at given fraction between ‘this’ point and specified point.
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @param   {number} fraction - Fraction between the two points (0 = this point, 1 = specified point).
 * @returns {LatLon} Intermediate point between this point and destination point.
 *
 * @example
 *   let p1 = new LL(52.205, 0.119);
 *   let p2 = new LL(48.857, 2.351);
 *   let pMid = p1.intermediatePointTo(p2, 0.25); // 51.3721°N, 000.7073°E
 */
public LL intermediatePointTo(LL point, double fraction) {

    double φ1 = toRadians(lat), λ1 = toRadians(lon);
    double φ2 = toRadians(point.lat), λ2 = toRadians(point.lon);
    double sinφ1 = sin(φ1), cosφ1 = cos(φ1), sinλ1 = sin(λ1), cosλ1 = cos(λ1);
    double sinφ2 = sin(φ2), cosφ2 = cos(φ2), sinλ2 = sin(λ2), cosλ2 = cos(λ2);

    // distance between points
    double Δφ = φ2 - φ1;
    double Δλ = λ2 - λ1;
    double a = sin(Δφ/2) * sin(Δφ/2)
        + cos(φ1) * cos(φ2) * sin(Δλ/2) * sin(Δλ/2);
    double δ = 2 * atan2(sqrt(a), sqrt(1-a));

    double A = sin((1-fraction)*δ) / sin(δ);
    double B = sin(fraction*δ) / sin(δ);

    double x = A * cosφ1 * cosλ1 + B * cosφ2 * cosλ2;
    double y = A * cosφ1 * sinλ1 + B * cosφ2 * sinλ2;
    double z = A * sinφ1 + B * sinφ2;

    double φ3 = atan2(z, sqrt(x*x + y*y));
    double λ3 = atan2(y, x);

    return new LL(toDegrees(φ3), (toDegrees(λ3)+540)%360-180); // normalise lon to −180..+180°
};


/**
 * Returns the destination point from ‘this’ point having travelled the given distance on the
 * given initial bearing (bearing normally varies around path followed).
 *
 * @param   {number} distance - Distance travelled, in same units as earth radius (default: metres).
 * @param   {number} bearing - Initial bearing in degrees from north.
 * @param   {number} [radius=6371e3] - (Mean) radius of earth (defaults to radius in metres).
 * @returns {LatLon} Destination point.
 *
 * @example
 *     double p1 = new LL(51.4778, -0.0015);
 *     double p2 = p1.destinationPoint(7794, 300.7); // 51.5135°N, 000.0983°W
 */
public LL destinationPoint(double distance, double bearing, double radius) {

    // sinφ2 = sinφ1⋅cosδ + cosφ1⋅sinδ⋅cosθ
    // tanΔλ = sinθ⋅sinδ⋅cosφ1 / cosδ−sinφ1⋅sinφ2
    // see mathforum.org/library/drmath/view/52049.html for derivation

    double δ = (distance) / radius; // angular distance in radians
    double θ = toRadians(bearing);

    double φ1 = toRadians(lat);
    double λ1 = toRadians(lon);

    double sinφ1 = sin(φ1), cosφ1 = cos(φ1);
    double sinδ = sin(δ), cosδ = cos(δ);
    double sinθ = sin(θ), cosθ = cos(θ);

    double sinφ2 = sinφ1*cosδ + cosφ1*sinδ*cosθ;
    double φ2 = asin(sinφ2);
    double y = sinθ * sinδ * cosφ1;
    double x = cosδ - sinφ1 * sinφ2;
    double λ2 = λ1 + atan2(y, x);

    return new LL(toDegrees(φ2), (toDegrees(λ2)+540)%360-180); // normalise to −180..+180°
};


/**
 * Returns the point of intersection of two paths defined by point and bearing.
 *
 * @param   {LatLon} p1 - First point.
 * @param   {number} brng1 - Initial bearing from first point.
 * @param   {LatLon} p2 - Second point.
 * @param   {number} brng2 - Initial bearing from second point.
 * @returns {LatLon|null} Destination point (null if no unique intersection defined).
 *
 * @example
 *     double p1 = LatLon(51.8853, 0.2545), brng1 = 108.547;
 *     double p2 = LatLon(49.0034, 2.5735), brng2 =  32.435;
 *     double pInt = LatLon.intersection(p1, brng1, p2, brng2); // 50.9078°N, 004.5084°E
 */
public static LL intersection(LL p1, double brng1, LL p2, double brng2) {

    // see www.edwilliams.org/avform.htm#Intersection

    double φ1 = toRadians(p1.lat), λ1 = toRadians(p1.lon);
    double φ2 = toRadians(p2.lat), λ2 = toRadians(p2.lon);
    double θ13 = toRadians(brng1), θ23 = toRadians(brng2);
    double Δφ = φ2-φ1, Δλ = λ2-λ1;

    // angular distance p1-p2
    double δ12 = 2*asin( sqrt( sin(Δφ/2)*sin(Δφ/2)
        + cos(φ1)*cos(φ2)*sin(Δλ/2)*sin(Δλ/2) ) );
    if (δ12 == 0) return null;

    // initial/final bearings between points
    double θa = acos( ( sin(φ2) - sin(φ1)*cos(δ12) ) / ( sin(δ12)*cos(φ1) ) );
    if (Double.isNaN(θa)) θa = 0; // protect against rounding
    double θb = acos( ( sin(φ1) - sin(φ2)*cos(δ12) ) / ( sin(δ12)*cos(φ2) ) );

    double θ12 = sin(λ2-λ1)>0 ? θa : 2*PI-θa;
    double θ21 = sin(λ2-λ1)>0 ? 2*PI-θb : θb;

    double α1 = θ13 - θ12; // angle 2-1-3
    double α2 = θ21 - θ23; // angle 1-2-3

    if (sin(α1)==0 && sin(α2)==0) return null; // infinite intersections
    if (sin(α1)*sin(α2) < 0) return null;      // ambiguous intersection

    double α3 = acos( -cos(α1)*cos(α2) + sin(α1)*sin(α2)*cos(δ12) );
    double δ13 = atan2( sin(δ12)*sin(α1)*sin(α2), cos(α2)+cos(α1)*cos(α3) );
    double φ3 = asin( sin(φ1)*cos(δ13) + cos(φ1)*sin(δ13)*cos(θ13) );
    double Δλ13 = atan2( sin(θ13)*sin(δ13)*cos(φ1), cos(δ13)-sin(φ1)*sin(φ3) );
    double λ3 = λ1 + Δλ13;

    return new LL(toDegrees(φ3), (toDegrees(λ3)+540)%360-180); // normalise to −180..+180°
};


/**
 * Returns (signed) distance from ‘this’ point to great circle defined by start-point and end-point.
 *
 * @param   {LatLon} pathStart - Start point of great circle path.
 * @param   {LatLon} pathEnd - End point of great circle path.
 * @param   {number} [radius=6371e3] - (Mean) radius of earth (defaults to radius in metres).
 * @returns {number} Distance to great circle (-ve if to left, +ve if to right of path).
 *
 * @example
 *   double pCurrent = new LL(53.2611, -0.7972);
 *   double p1 = new LL(53.3206, -1.7297);
 *   double p2 = new LL(53.1887,  0.1334);
 *   double d = pCurrent.crossTrackDistanceTo(p1, p2);  // -307.5 m
 */
public double crossTrackDistanceTo(LL pathStart, LL pathEnd, double radius) {

    double δ13 = pathStart.distanceTo(this, radius) / radius;
    double θ13 = toRadians(pathStart.bearingTo(this));
    double θ12 = toRadians(pathStart.bearingTo(pathEnd));

    double δxt = asin(sin(δ13) * sin(θ13-θ12));

    return δxt * radius;
};


/**
 * Returns how far ‘this’ point is along a path from from start-point, heading towards end-point.
 * That is, if a perpendicular is drawn from ‘this’ point to the (great circle) path, the along-track
 * distance is the distance from the start point to where the perpendicular crosses the path.
 *
 * @param   {LatLon} pathStart - Start point of great circle path.
 * @param   {LatLon} pathEnd - End point of great circle path.
 * @param   {number} [radius=6371e3] - (Mean) radius of earth (defaults to radius in metres).
 * @returns {number} Distance along great circle to point nearest ‘this’ point.
 *
 * @example
 *   double pCurrent = new LL(53.2611, -0.7972);
 *   double p1 = new LL(53.3206, -1.7297);
 *   double p2 = new LL(53.1887,  0.1334);
 *   double d = pCurrent.alongTrackDistanceTo(p1, p2);  // 62.331 km
 */
public double alongTrackDistanceTo(LL pathStart, LL pathEnd, double radius) {

    double δ13 = pathStart.distanceTo(this, radius) / radius;
    double θ13 = toRadians(pathStart.bearingTo(this));
    double θ12 = toRadians(pathStart.bearingTo(pathEnd));

    double δxt = asin(sin(δ13) * sin(θ13-θ12));

    double δat = acos(cos(δ13) / abs(cos(δxt)));

    return δat*signum(cos(θ12-θ13)) * radius;
};


/**
 * Returns maximum latitude reached when traveling on a great circle on given bearing from this
 * point ('Clairaut's formula'). Negate the result for the minimum latitude (in the Southern
 * hemisphere).
 *
 * The maximum latitude is independent of longitude; it will be the same for all points on a given
 * latitude.
 *
 * @param {number} bearing - Initial bearing.
 * @param {number} latitude - Starting latitude.
 */
public double maxLatitude(long bearing) {
    double θ = toRadians(bearing);

    double φ = toRadians(lat);

    double φMax = acos(abs(sin(θ)*cos(φ)));

    return toRadians(φMax);
};


/**
 * Returns the pair of meridians at which a great circle defined by two points crosses the given
 * latitude. If the great circle doesn't reach the given latitude, null is returned.
 *
 * @param {LatLon} point1 - First point defining great circle.
 * @param {LatLon} point2 - Second point defining great circle.
 * @param {number} latitude - Latitude crossings are to be determined for.
 * @returns {Object|null} Object containing { lon1, lon2 } or null if given latitude not reached.
 */
public static double[] crossingParallels(LL point1, LL point2, double latitude) {
    double φ = toRadians(latitude);

    double φ1 = toRadians(point1.lat);
    double λ1 = toRadians(point1.lon);
    double φ2 = toRadians(point2.lat);
    double λ2 = toRadians(point2.lon);

    double Δλ = λ2 - λ1;

    double x = sin(φ1) * cos(φ2) * cos(φ) * sin(Δλ);
    double y = sin(φ1) * cos(φ2) * cos(φ) * cos(Δλ) - cos(φ1) * sin(φ2) * cos(φ);
    double z = cos(φ1) * cos(φ2) * sin(φ) * sin(Δλ);

    if (z*z > x*x + y*y) return null; // great circle doesn't reach latitude

    double λm = atan2(-y, x);                  // longitude at max latitude
    double Δλi = acos(z / sqrt(x*x+y*y)); // Δλ from λm to intersection points

    double λi1 = λ1 + λm - Δλi;
    double λi2 = λ1 + λm + Δλi;

    return new double[]{  (toDegrees(λi1)+540)%360-180,  (toDegrees(λi2)+540)%360-180 }; // normalise to −180..+180°
};


/* Rhumb - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  */

/**
 * Returns the distance traveling from ‘this’ point to destination point along a rhumb line.
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @param   {number} [radius=6371e3] - (Mean) radius of earth (defaults to radius in metres).
 * @returns {number} Distance in km between this point and destination point (same units as radius).
 *
 * @example
 *     double p1 = new LL(51.127, 1.338);
 *     double p2 = new LL(50.964, 1.853);
 *     double d = p1.distanceTo(p2); // 40.31 km
 */
public double rhumbDistanceTo(LL point, double R) {
    double φ1 = toRadians(lat), φ2 = toRadians(point.lat);
    double Δφ = φ2 - φ1;
    double Δλ = toRadians(abs(point.lon-lon));
    // if dLon over 180° take shorter rhumb line across the anti-meridian:
    if (Δλ > PI) Δλ -= 2*PI;

    // on Mercator projection, longitude distances shrink by latitude; q is the 'stretch factor'
    // q becomes ill-conditioned along E-W line (0/0); use empirical tolerance to avoid it
    double Δψ = log(tan(φ2/2+PI/4)/tan(φ1/2+PI/4));
    double q = abs(Δψ) > 10e-12 ? Δφ/Δψ : cos(φ1);

    // distance is pythagoras on 'stretched' Mercator projection
    double δ = sqrt(Δφ*Δφ + q*q*Δλ*Δλ); // angular distance in radians
    double dist = δ * R;

    return dist;
};


/**
 * Returns the bearing from ‘this’ point to destination point along a rhumb line.
 *
 * @param   {LatLon} point - Latitude/longitude of destination point.
 * @returns {number} Bearing in degrees from north.
 *
 * @example
 *     double p1 = new LL(51.127, 1.338);
 *     double p2 = new LL(50.964, 1.853);
 *     double d = p1.rhumbBearingTo(p2); // 116.7 m
 */
public double rhumbBearingTo(LL point) {

    double φ1 = toRadians(lat), φ2 = toRadians(point.lat);
    double Δλ = toRadians(point.lon-lon);
    // if dLon over 180° take shorter rhumb line across the anti-meridian:
    if (Δλ >  PI) Δλ -= 2*PI;
    if (Δλ < -PI) Δλ += 2*PI;

    double Δψ = log(tan(φ2/2+PI/4)/tan(φ1/2+PI/4));

    double θ = atan2(Δλ, Δψ);

    return (toDegrees(θ)+360) % 360;
};


/**
 * Returns the destination point having travelled along a rhumb line from ‘this’ point the given
 * distance on the  given bearing.
 *
 * @param   {number} distance - Distance travelled, in same units as earth radius (default: metres).
 * @param   {number} bearing - Bearing in degrees from north.
 * @param   {number} [radius=6371e3] - (Mean) radius of earth (defaults to radius in metres).
 * @returns {LatLon} Destination point.
 *
 * @example
 *     double p1 = new LL(51.127, 1.338);
 *     double p2 = p1.rhumbDestinationPoint(40300, 116.7); // 50.9642°N, 001.8530°E
 */
public LL rhumbDestinationPoint(double distance, double bearing, double radius) {

    double δ = (distance) / radius; // angular distance in radians
    double φ1 = toRadians(lat), λ1 = toRadians(lon);
    double θ = toRadians(bearing);

    double Δφ = δ * cos(θ);
    double φ2 = φ1 + Δφ;

    // check for some daft bugger going past the pole, normalise latitude if so
    if (abs(φ2) > PI/2) φ2 = φ2>0 ? PI-φ2 : -PI-φ2;

    double Δψ = log(tan(φ2/2+PI/4)/tan(φ1/2+PI/4));
    double q = abs(Δψ) > 10e-12 ? Δφ / Δψ : cos(φ1); // E-W course becomes ill-conditioned with 0/0

    double Δλ = δ*sin(θ)/q;
    double λ2 = λ1 + Δλ;

    return new LL(toDegrees(φ2), (toDegrees(λ2)+540) % 360 - 180); // normalise to −180..+180°
};


/**
 * Returns the loxodromic midpoint (along a rhumb line) between ‘this’ point and second point.
 *
 * @param   {LatLon} point - Latitude/longitude of second point.
 * @returns {LatLon} Midpoint between this point and second point.
 *
 * @example
 *     double p1 = new LL(51.127, 1.338);
 *     double p2 = new LL(50.964, 1.853);
 *     double pMid = p1.rhumbMidpointTo(p2); // 51.0455°N, 001.5957°E
 */
public LL rhumbMidpointTo(LL point) {

    // see mathforum.org/kb/message.jspa?messageID=148837

    double φ1 = toRadians(lat), λ1 = toRadians(lon);
    double φ2 = toRadians(point.lat), λ2 = toRadians(point.lon);

    if (abs(λ2-λ1) > PI) λ1 += 2*PI; // crossing anti-meridian

    double φ3 = (φ1+φ2)/2;
    double f1 = tan(PI/4 + φ1/2);
    double f2 = tan(PI/4 + φ2/2);
    double f3 = tan(PI/4 + φ3/2);
    double λ3 = ( (λ2-λ1)*log(f3) + λ1*log(f2) - λ2*log(f1) ) / log(f2/f1);

    if (Double.isInfinite(λ3)) λ3 = (λ1+λ2)/2; // parallel of latitude

    LL p = new LL(toDegrees(φ3), (toDegrees(λ3)+540)%360-180); // normalise to −180..+180°

    return p;
};

}
