from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select, or_, and_

from .. import push
from ..db import get_session
from ..tables import Friendship, User, make_pair_key
from .deps import registered_user
from .me import public_profile

router = APIRouter(prefix="/friends", tags=["friends"])


class FriendRequestIn(BaseModel):
    email: str


class FavoriteIn(BaseModel):
    favorite: bool


def find_friendship(s: Session, a: str, b: str) -> Friendship | None:
    return s.exec(select(Friendship).where(Friendship.pair_key == make_pair_key(a, b))).first()


def are_friends(s: Session, a: str, b: str) -> bool:
    f = find_friendship(s, a, b)
    return f is not None and f.status == "accepted"


def favorite_friend_uids(s: Session, uid: str) -> list[str]:
    """Accepted friends that `uid` marked as favorite (they get notified about uid's alerts)."""
    rows = s.exec(select(Friendship).where(
        Friendship.status == "accepted",
        or_(and_(Friendship.requester_uid == uid, Friendship.requester_favorite == True),   # noqa: E712
            and_(Friendship.addressee_uid == uid, Friendship.addressee_favorite == True)))).all()  # noqa: E712
    return [f.addressee_uid if f.requester_uid == uid else f.requester_uid for f in rows]


def view(f: Friendship, me: str, s: Session) -> dict:
    other_uid = f.addressee_uid if f.requester_uid == me else f.requester_uid
    other = s.get(User, other_uid)
    i_am_requester = f.requester_uid == me
    return {
        "id": f.id,
        "friend": public_profile(other),
        "status": f.status,
        "direction": "outgoing" if i_am_requester else "incoming",
        "favorite": f.requester_favorite if i_am_requester else f.addressee_favorite,
    }


@router.post("", status_code=201)
def send_request(body: FriendRequestIn, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    other = s.exec(select(User).where(User.email == body.email.strip().lower())).first()
    if not other:
        raise HTTPException(404, "no user with that email")
    if other.uid == me.uid:
        raise HTTPException(422, "you cannot add yourself")
    # Fast path for the normal case: a friendly 409 without trying the insert.
    if find_friendship(s, me.uid, other.uid):
        raise HTTPException(409, "request or friendship already exists")
    # Race case: two requests can both pass the check above. The UNIQUE pair_key makes the
    # database reject the second insert, and we turn that into the same 409.
    f = Friendship(requester_uid=me.uid, addressee_uid=other.uid,
                   pair_key=make_pair_key(me.uid, other.uid))
    try:
        s.add(f); s.commit()
    except IntegrityError:
        s.rollback()
        raise HTTPException(409, "request or friendship already exists")
    s.refresh(f)
    push.send(other.fcm_token, "New friend request",
              f"{me.display_name or me.email} wants to be your posture buddy",
              {"type": "friend_request", "friendship_id": f.id})
    return view(f, me.uid, s)


@router.get("")
def list_friends(me: User = Depends(registered_user), s: Session = Depends(get_session)):
    rows = s.exec(select(Friendship).where(
        or_(Friendship.requester_uid == me.uid, Friendship.addressee_uid == me.uid))).all()
    return [view(f, me.uid, s) for f in rows]


@router.post("/{friendship_id}/accept")
def accept(friendship_id: int, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    f = s.get(Friendship, friendship_id)
    if not f or f.addressee_uid != me.uid:          # only the addressee can accept
        raise HTTPException(404, "friend request not found")
    f.status = "accepted"
    s.add(f); s.commit(); s.refresh(f)
    requester = s.get(User, f.requester_uid)
    push.send(requester.fcm_token, "Friend request accepted",
              f"{me.display_name or me.email} accepted your request",
              {"type": "friend_accepted", "friendship_id": f.id})
    return view(f, me.uid, s)


@router.put("/{friendship_id}/favorite")
def set_favorite(friendship_id: int, body: FavoriteIn, me: User = Depends(registered_user),
                 s: Session = Depends(get_session)):
    f = s.get(Friendship, friendship_id)
    if not f or me.uid not in (f.requester_uid, f.addressee_uid):
        raise HTTPException(404, "friendship not found")
    if f.status != "accepted":
        raise HTTPException(409, "friend request not accepted yet")
    if f.requester_uid == me.uid:
        f.requester_favorite = body.favorite
    else:
        f.addressee_favorite = body.favorite
    s.add(f); s.commit(); s.refresh(f)
    return view(f, me.uid, s)


@router.delete("/{friendship_id}", status_code=204)
def remove(friendship_id: int, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    """Unfriend, decline a request, or cancel your own request."""
    f = s.get(Friendship, friendship_id)
    if not f or me.uid not in (f.requester_uid, f.addressee_uid):
        raise HTTPException(404, "friendship not found")
    s.delete(f); s.commit()
