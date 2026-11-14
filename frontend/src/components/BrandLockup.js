import { productName } from "../brand";

export default function BrandLockup() {
  return (
    <span className="brand-lockup">
      <img src={`${process.env.PUBLIC_URL}/collab-mark.svg`} width="36" height="36" alt="" />
      <span>{productName}</span>
    </span>
  );
}
