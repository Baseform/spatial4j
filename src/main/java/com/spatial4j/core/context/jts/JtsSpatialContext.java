/*******************************************************************************
 * Copyright (c) 2015 Voyager Search and MITRE
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 *    http://www.apache.org/licenses/LICENSE-2.0.txt
 ******************************************************************************/

package com.spatial4j.core.context.jts;

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.exception.InvalidShapeException;
import com.spatial4j.core.io.jts.JtsWKTReader;
import com.spatial4j.core.shape.Circle;
import com.spatial4j.core.shape.Point;
import com.spatial4j.core.shape.Rectangle;
import com.spatial4j.core.shape.Shape;
import com.spatial4j.core.shape.jts.JtsGeometry;
import com.spatial4j.core.shape.jts.JtsPoint;
import com.vividsolutions.jts.algorithm.CGAlgorithms;
import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.util.GeometricShapeFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Enhances the default {@link SpatialContext} with support for Polygons (and
 * other geometries) using <a href="https://sourceforge.net/projects/jts-topo-suite/">JTS</a>.
 * To the extent possible, our {@link JtsGeometry} adds some amount of geodetic support over
 * vanilla JTS which only has a Euclidean (flat plane) model.
 */
public class JtsSpatialContext extends SpatialContext {

  public static final JtsSpatialContext GEO;
  static {
    JtsSpatialContextFactory factory = new JtsSpatialContextFactory();
    factory.geo = true;
    GEO = new JtsSpatialContext(factory);
  }

  protected final GeometryFactory geometryFactory;

  protected final boolean allowMultiOverlap;
  protected final boolean useJtsPoint;
  protected final boolean useJtsLineString;
  protected final DatelineRule datelineRule;

  /**
   * Called by {@link com.spatial4j.core.context.jts.JtsSpatialContextFactory#newSpatialContext()}.
   */
  public JtsSpatialContext(JtsSpatialContextFactory factory) {
    super(factory);
    this.geometryFactory = factory.getGeometryFactory();

    this.allowMultiOverlap = factory.allowMultiOverlap;
    this.useJtsPoint = factory.useJtsPoint;
    this.useJtsLineString = factory.useJtsLineString;
    this.datelineRule = factory.datelineRule;
  }

  /**
   * If geom might be a multi geometry of some kind, then might multiple
   * component geometries overlap? Strict OGC says this is invalid but we
   * can accept it by computing the union. Note: Our ShapeCollection mostly
   * doesn't care but it has a method related to this
   * {@link com.spatial4j.core.shape.ShapeCollection#relateContainsShortCircuits()}.
   */
  public boolean isAllowMultiOverlap() {
    return allowMultiOverlap;
  }

  /**
   * Returns the rule used to handle geometry objects that have dateline crossing considerations.
   */
  public DatelineRule getDatelineRule() {
    return datelineRule;
  }

  @Override
  public double normX(double x) {
    x = super.normX(x);
    return geometryFactory.getPrecisionModel().makePrecise(x);
  }

  @Override
  public double normY(double y) {
    y = super.normY(y);
    return geometryFactory.getPrecisionModel().makePrecise(y);
  }

  @Override
  public String toString(Shape shape) {
    //Note: this logic is from the defunct JtsShapeReadWriter
    if (shape instanceof JtsGeometry) {
      JtsGeometry jtsGeom = (JtsGeometry) shape;
      return jtsGeom.getGeom().toText();
    }
    //Note: doesn't handle ShapeCollection or BufferedLineString
    return super.toString(shape);
  }

  /**
   * Gets a JTS {@link Geometry} for the given {@link Shape}. Some shapes hold a
   * JTS geometry whereas new ones must be created for the rest.
   * @param shape Not null
   * @return Not null
   */
  public Geometry getGeometryFrom(Shape shape) {
    if (shape instanceof JtsGeometry) {
      return ((JtsGeometry)shape).getGeom();
    }
    if (shape instanceof JtsPoint) {
      return ((JtsPoint) shape).getGeom();
    }
    if (shape instanceof Point) {
      Point point = (Point) shape;
      return geometryFactory.createPoint(new Coordinate(point.getX(),point.getY()));
    }
    if (shape instanceof Rectangle) {
      Rectangle r = (Rectangle)shape;
      if (r.getCrossesDateLine()) {
        Collection<Geometry> pair = new ArrayList<Geometry>(2);
        pair.add(geometryFactory.toGeometry(new Envelope(
                r.getMinX(), getWorldBounds().getMaxX(), r.getMinY(), r.getMaxY())));
        pair.add(geometryFactory.toGeometry(new Envelope(
                getWorldBounds().getMinX(), r.getMaxX(), r.getMinY(), r.getMaxY())));
        return geometryFactory.buildGeometry(pair);//a MultiPolygon or MultiLineString
      } else {
        return geometryFactory.toGeometry(new Envelope(r.getMinX(), r.getMaxX(), r.getMinY(), r.getMaxY()));
      }
    }
    if (shape instanceof Circle) {
      // FYI Some interesting code for this is here:
      //  http://docs.codehaus.org/display/GEOTDOC/01+How+to+Create+a+Geometry#01HowtoCreateaGeometry-CreatingaCircle
      //TODO This should ideally have a geodetic version
      Circle circle = (Circle)shape;
      if (circle.getBoundingBox().getCrossesDateLine())
        throw new IllegalArgumentException("Doesn't support dateline cross yet: "+circle);//TODO
      GeometricShapeFactory gsf = new GeometricShapeFactory(geometryFactory);
      gsf.setSize(circle.getBoundingBox().getWidth());
      gsf.setNumPoints(4*25);//multiple of 4 is best
      gsf.setCentre(new Coordinate(circle.getCenter().getX(), circle.getCenter().getY()));
      return gsf.createCircle();
    }
    //TODO add BufferedLineString
    throw new InvalidShapeException("can't make Geometry from: " + shape);
  }

  /** Should {@link #makePoint(double, double)} return {@link JtsPoint}? */
  public boolean useJtsPoint() {
    return useJtsPoint;
  }

  @Override
  public Point makePoint(double x, double y) {
    if (!useJtsPoint())
      return super.makePoint(x, y);
    //A Jts Point is fairly heavyweight!  TODO could/should we optimize this? SingleCoordinateSequence
    verifyX(x);
    verifyY(y);
    Coordinate coord = Double.isNaN(x) ? null : new Coordinate(x, y);
    return new JtsPoint(geometryFactory.createPoint(coord), this);
  }

  /** Should {@link #makeLineString(java.util.List)} return {@link JtsGeometry}? */
  public boolean useJtsLineString() {
    //BufferedLineString doesn't yet do dateline cross, and can't yet be relate()'ed with a
    // JTS geometry
    return useJtsLineString;
  }

  @Override
  public Shape makeLineString(List<Point> points) {
    if (!useJtsLineString())
      return super.makeLineString(points);
    //convert List<Point> to Coordinate[]
    Coordinate[] coords = new Coordinate[points.size()];
    for (int i = 0; i < coords.length; i++) {
      Point p = points.get(i);
      if (p instanceof JtsPoint) {
        JtsPoint jtsPoint = (JtsPoint) p;
        coords[i] = jtsPoint.getGeom().getCoordinate();
      } else {
        coords[i] = new Coordinate(p.getX(), p.getY());
      }
    }
    LineString lineString = geometryFactory.createLineString(coords);
    return makeShape(lineString);
  }

  /**
   * INTERNAL: See {@link JtsWKTReader#makeShapeFromGeometry(Geometry)}.  This method is particularly
   * suitable when the geometry has come from user input.
   */
  public Shape makeShapeFromGeometry(Geometry geom) {
    // note: the arrangement here is clearly a hack in that we reference a method (and validate/repair
    //  config state) on the WKT instance even though it's not related to WKT.  TODO fix this.
    JtsWKTReader jtsWKTReader = (JtsWKTReader) getFormats().getWktReader();
    return jtsWKTReader.makeShapeFromGeometry(geom); // will in turn call makeShape(geom) above
  }

  /**
   * INTERNAL
   * @see #makeShape(com.vividsolutions.jts.geom.Geometry)
   *
   * @param geom Non-null
   * @param dateline180Check if both this is true and {@link #isGeo()}, then JtsGeometry will check
   *                         for adjacent coordinates greater than 180 degrees longitude apart, and
   *                         it will do tricks to make that line segment (and the shape as a whole)
   *                         cross the dateline even though JTS doesn't have geodetic support.
   * @param allowMultiOverlap See {@link #isAllowMultiOverlap()}.
   */
  public JtsGeometry makeShape(Geometry geom, boolean dateline180Check, boolean allowMultiOverlap) {
    return new JtsGeometry(geom, this, dateline180Check, allowMultiOverlap);
  }

  /**
   * INTERNAL: Creates a {@link Shape} from a JTS {@link Geometry}. Generally, this shouldn't be
   * called when one of the other factory methods are available, such as for points. The caller
   * needs to have done some verification/normalization of the coordinates by now, if any.  Also,
   * note that direct instances of {@link GeometryCollection} isn't supported.
   *
   * Instead of calling this method, consider {@link JtsWKTReader#makeShapeFromGeometry(Geometry)}
   * which
   */
  public JtsGeometry makeShape(Geometry geom) {
    return makeShape(geom, datelineRule != DatelineRule.none, allowMultiOverlap);
  }

  public GeometryFactory getGeometryFactory() {
    return geometryFactory;
  }

  @Override
  public String toString() {
    if (this.equals(GEO)) {
      return GEO.getClass().getSimpleName()+".GEO";
    } else {
      return super.toString();
    }
  }

  /**
   * INTERNAL: Returns a Rectangle of the JTS {@link Envelope} (bounding box) of the given {@code geom}.  This asserts
   * that {@link Geometry#isRectangle()} is true.  This method reacts to the {@link DatelineRule} setting.
   * @param geom non-null
   * @return null equivalent Rectangle.
   */
  public Rectangle makeRectFromRectangularPoly(Geometry geom) {
    // TODO although, might want to never convert if there's a semantic difference (e.g.
    //  geodetically)? Should have a setting for that.
    assert geom.isRectangle();
    Envelope env = geom.getEnvelopeInternal();
    boolean crossesDateline = false;
    if (isGeo() && getDatelineRule() != DatelineRule.none) {
      if (getDatelineRule() == DatelineRule.ccwRect) {
        // If JTS says it is clockwise, then it's actually a dateline crossing rectangle.
        crossesDateline = !CGAlgorithms.isCCW(geom.getCoordinates());
      } else {
        crossesDateline = env.getWidth() > 180;
      }
    }
    if (crossesDateline)
      return makeRectangle(env.getMaxX(), env.getMinX(), env.getMinY(), env.getMaxY());
    else
      return makeRectangle(env.getMinX(), env.getMaxX(), env.getMinY(), env.getMaxY());
  }
}