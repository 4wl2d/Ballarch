package ballarch.examples.itinerary.app

import ballarch.examples.itinerary.ItineraryFlow
import ballarch.examples.trip.FlightReply
import ballarch.examples.trip.HotelReply
import ballarch.examples.trip.TripFlow
import ballarch.examples.trip.TripRef
import ballarch.examples.trip.app.tripComposition
import ballarch.runtime.Adapter
import ballarch.runtime.Composition

/** The itinerary over the trip, in one runtime. Its calls to a trip wait for the trip's worst case. */
fun itineraryComposition(
    flightBook: Adapter<TripRef, FlightReply>,
    flightVoid: Adapter<TripRef, Unit>,
    hotelBook: Adapter<TripRef, HotelReply>,
    hotelCancel: Adapter<TripRef, Unit>,
    trip: TripFlow = TripFlow(),
    itinerary: ItineraryFlow = ItineraryFlow(tripTimeoutMillis = trip.worstCaseMillis + 1_000),
): Composition = tripComposition(flightBook, flightVoid, hotelBook, hotelCancel, trip).ball(itinerary)
