/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the
 * NOTICE file distributed with this work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

REGISTER osmx.jar;
REGISTER pigeon.jar
REGISTER esri-geometry-api-1.0.jar;

IMPORT 'pigeon_import.pig';

/* Read and parse nodes */
xml_nodes = LOAD 'map.osm'
  USING org.apache.pig.piggybank.storage.XMLLoader('node')
  AS (node:chararray);

parsed_nodes = FOREACH xml_nodes
  GENERATE edu.umn.cs.spatialHadoop.udf.OSMNode(node) AS node;
  
/*filtered_nodes = FILTER parsed_nodes BY node.tags#'highway' == 'traffic_signals';*/
filtered_nodes = parsed_nodes; /* No filter */

flattened_nodes = FOREACH filtered_nodes
  GENERATE node.id, node.lon, node.lat, node.tags;

/*STORE flattened_nodes into 'points';*/

flattened_nodes_wkt = FOREACH flattened_nodes
  GENERATE id, ST_AsText(ST_MakePoint(lon, lat)), tags;

/*STORE flattened_nodes_wkt into '/all_points.tsv';*/

/******************************************************/
/* Read and parse ways */
xml_ways = LOAD 'map.osm'
  USING org.apache.pig.piggybank.storage.XMLLoader('way') AS (way:chararray);

parsed_ways = FOREACH xml_ways
  GENERATE edu.umn.cs.spatialHadoop.udf.OSMWay(way) AS way;
  
/* Filter ways to keep only ways of interest */
/*filtered_ways = FILTER parsed_ways BY way.tags#'boundary' == 'administrative';*/
filtered_ways = parsed_ways;

/* Project columns of interest in ways*/
flattened_ways = FOREACH filtered_ways
  GENERATE way.id AS way_id, FLATTEN(way.nodes), way.tags AS tags;

/* Project node ID and point location*/
node_locations = FOREACH parsed_nodes
  GENERATE node.id, ST_MakePoint(node.lon, node.lat) AS location;

/* Join ways with nodes to find the location of each node (lat, lon)*/
joined_ways = JOIN node_locations BY id, flattened_ways BY node_id;

/* Group all node locations of each way*/
ways_with_nodes = GROUP joined_ways BY way_id;

/* For each way, generate a shape out of every list of points*/
ways_with_shapes = FOREACH ways_with_nodes {
  /* order points by position */
  ordered = ORDER joined_ways BY pos;
  /* All tags are similar. Just grab the first one*/
  tags = FOREACH joined_ways GENERATE tags;
  GENERATE group AS way_id, ST_AsText(ST_MakeLine(ordered.location)),
    FLATTEN(TOP(1, 0, tags)) AS tags;
};

STORE ways_with_shapes into 'all_ways.tsv';

