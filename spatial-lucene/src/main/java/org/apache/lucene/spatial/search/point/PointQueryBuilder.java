/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.spatial.search.point;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.function.ValueSource;
import org.apache.lucene.search.function.ValueSourceQuery;
import org.apache.lucene.spatial.base.BBox;
import org.apache.lucene.spatial.base.Point;
import org.apache.lucene.spatial.base.Radius;
import org.apache.lucene.spatial.base.SpatialArgs;
import org.apache.lucene.spatial.base.distance.DistanceCalculator;
import org.apache.lucene.spatial.base.distance.EuclidianDistanceCalculator;
import org.apache.lucene.spatial.search.SpatialQueryBuilder;


public class PointQueryBuilder extends SpatialQueryBuilder
{
  public static final String SUFFIX_X = "__x";
  public static final String SUFFIX_Y = "__y";

  @Override
  public ValueSource makeValueSource(String fname, SpatialArgs args)
  {
    DistanceCalculator calc = new EuclidianDistanceCalculator();
    String[] fields = new String[] {fname+SUFFIX_X,fname+SUFFIX_Y};
    if( args.shape instanceof Radius ) {
      DistanceValueSource vs = new DistanceValueSource( ((Radius)args.shape).getPoint(),
          calc, fields );
      vs.max = ((Radius)args.shape).getRadius();
      return vs;
    }
    if( args.shape instanceof Point ) {
      return new DistanceValueSource( ((Point)args.shape), calc, fields );
    }
    throw new UnsupportedOperationException( "score only works with point or radius (for now)" );
  }

  @Override
  public Query makeQuery(String fname, SpatialArgs args)
  {
    // For starters, just limit the bbox
    BBox bbox = args.shape.getBoundingBox();
    if( bbox.getCrossesDateLine() ) {
      throw new UnsupportedOperationException( "Crossing dateline not yet supported" );
    }

    PointQueryHelper helper = new PointQueryHelper(bbox,fname+SUFFIX_X,fname+SUFFIX_Y);
    Query spatial = null;
    switch( args.op )
    {
      case BBoxIntersects: spatial = helper.makeWithin(); break;
      case BBoxWithin: spatial =  helper.makeWithin(); break;
      case Contains: spatial =  helper.makeWithin(); break;
      case Intersects: spatial =  helper.makeWithin(); break;
      case IsWithin: spatial =  helper.makeWithin(); break;
      case Overlaps: spatial =  helper.makeWithin(); break;
//      case IsEqualTo: spatial =  helper.makeEquals(); break;
//      case IsDisjointTo: spatial =  helper.makeDisjoint(); break;
      default:
        throw new UnsupportedOperationException( args.op.name() );
    }

    if( args.calculateScore ) {
      Query spatialRankingQuery = new ValueSourceQuery( makeValueSource( fname, args ) );
      BooleanQuery bq = new BooleanQuery();
      bq.add(spatial,BooleanClause.Occur.MUST);
      bq.add(spatialRankingQuery,BooleanClause.Occur.MUST);
      return bq;
    }
    return spatial;
  }
}


class PointQueryHelper
{
  final BBox queryExtent;
  final String fieldX;
  final String fieldY;

  public PointQueryHelper( BBox bbox, String x, String y )
  {
    this.queryExtent = bbox;
    this.fieldX = x;
    this.fieldY = y;
  }

  //-------------------------------------------------------------------------------
  //
  //-------------------------------------------------------------------------------

  /**
   * Constructs a query to retrieve documents that fully contain the input envelope.
   * @return the spatial query
   */
  Query makeWithin()
  {
    Query qX = NumericRangeQuery.newDoubleRange(fieldX,queryExtent.getMinX(),queryExtent.getMaxX(),true,true);
    Query qY = NumericRangeQuery.newDoubleRange(fieldY,queryExtent.getMinY(),queryExtent.getMaxY(),true,true);

    BooleanQuery bq = new BooleanQuery();
    bq.add(qX,BooleanClause.Occur.MUST);
    bq.add(qY,BooleanClause.Occur.MUST);
    return bq;
  }
}



