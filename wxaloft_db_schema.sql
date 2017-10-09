/*
 * Each individual weather observation gets an entry in this table. It
 * is possible for one ACARS message to code multiple observations.
 *
 * Note that the rows in this table are fixed-size. That is deliberate;
 * this is by far the most active table, and efficiency considerations
 * are thus paramount here.
 */
create table observations (
    id          bigint not null auto_increment primary key,
    received    timestamp null,
    observed    timestamp null,
    frequency   double,
    client_id   int not null,
    altitude    int,
    wind_speed  smallint,
    wind_dir    smallint,
    temperature float,
    source      char(7),
    latitude    double,
    longitude   double );
    
/* when we delete an observation, we should delete from obs_area, too */
delimeter $EOD
create trigger obs_delete after delete on observations 
    for each row begin
        delete from obs_area where observation_id = old.id;
    end;
delimeter ;

/*
 * Each authorized client of the web service that receives ACARS data.
 */
create table clients (
    id          int not null auto_increment primary key,
    auth        varbinary(64) not null,
    name        varchar(32),
    location_id int not null,
    log_all     boolean,
    record_wx   boolean );
    
create unique index auth_ndx on clients (auth);

/*
 * A location that an authorized client may be at. Note that there might
 * be multiple clients at the same location.
 */
create table locations (
    id          int not null auto_increment primary key,
    line1       varchar(64),
    line2       varchar(64),
    line3       varchar(64),
    city        varchar(32),
    region      varchar(32),
    postcode    varchar(16),
    country     varchar(2),
    pri_phone   varchar(32),
    alt_phone   varchar(32),
    email       varchar(64) );
    
/*
 * Some clients will report "channel numbers" in the "channel" element,
 * others the frequency in MHz. This table allows the channel numbers
 * to be translated into MHz.
 */
create table frequencies (
    id          int not null auto_increment primary key,
    client_id   int not null,
    channel     tinyint not null,
    frequency   double );

create unique index clch_ndx on frequencies (client_id, channel);

/*
 * The areas that we support. Each area is associated with a web page that
 * displays a map of recent weather observations.
 */
create table areas (
    id          int not null auto_increment primary key,
    name        varchar(4) not null,
    city        varchar(32),
    region      varchar(32),
    country     varchar(2),
    timezone    varchar(32),
    latitude    double,
    longitude   double,
    map_bg      varchar(64) );
    
create unique index name_ndx on areas (name);

/*
 * Each observation falls within the scope of zero or more areas.
 */
create table obs_area (
    id          bigint not null auto_increment primary key,
    observation_id bigint not null,
    area_id     int not null );
    
create index obs_ndx on obs_area (observation_id);

/* distance in km between two lat/long points */
delimiter $EOD
create function
    kilometers(lat1 double, lon1 double, lat2 double, lon2 double)
    returns double deterministic
    begin
        declare radius, chord, r2, c2 double;
        set lat1 = radians(lat1);
        set lon1 = radians(lon1);
        set lat2 = radians(lat2);
        set lon2 = radians(lon2);
        set radius = 6378.0;
        set r2 = radius * radius;
        set chord = radius * sqrt(
            2.0*(1.0-cos(lat1)*cos(lat2)*cos(lon1-lon2)-sin(lat1)*sin(lat2)));
        set c2 = chord * chord;
        return radius * asin(chord/(2.0*r2)*sqrt(4.0*r2-c2));
    end$EOD
delimiter ;
