-- Operations' ride list, newest first, with and without a status filter (LLD §4.5, §13.5).

CREATE INDEX rides_newest ON ride.rides (requested_at DESC, id DESC);
CREATE INDEX rides_by_status ON ride.rides (status, requested_at DESC, id DESC);
